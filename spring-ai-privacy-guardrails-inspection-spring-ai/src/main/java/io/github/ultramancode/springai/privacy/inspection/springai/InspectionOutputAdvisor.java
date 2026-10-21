package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.OutputResponseContext;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Inspects assistant text before returning it to the application, including {@code returnDirect} tool results.
 * Provider-specific reasoning fields, tool-call arguments and metadata are excluded from inspection.
 *
 * <p>Inspection uses the response after any configured privacy output processing.
 * Text and frame limits apply to this response. The output status is {@code PROCESSED}
 * when privacy processing completed for that text, and {@code UNKNOWN} otherwise.
 *
 * <p>Each output body is inspected as a complete text segment, including JSON keys, values,
 * structure and escape sequences. Rules match the text without JSON unescaping.
 * Inspection never rewrites the returned response.
 * Complete {@code returnDirect} tool results are inspected separately from preceding model text.
 *
 * <p>Streaming responses are collected before inspection, including intermediate text from the tool loop.
 * Collected response chunks are delivered in their original order only after inspection allows the complete output.
 * If inspection blocks the response, or stream collection fails or is cancelled, no buffered
 * content is delivered. The stream collection timeout includes model calls and tool execution.
 */
public final class InspectionOutputAdvisor implements CallAdvisor, StreamAdvisor, PriorityOrdered {
    /** Default maximum number of output frames collected per streamed invocation. */
    public static final int DEFAULT_MAX_FRAMES = 4096;
    /** Default total duration allowed for stream collection. */
    public static final Duration DEFAULT_STREAM_TIMEOUT = Duration.ofSeconds(60);
    /** Orders output inspection after privacy output protection. */
    public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    private final InspectionEnforcement inspectionEnforcement;
    private final InspectionLimits inspectionLimits;
    private final int maxFrames;
    private final Duration streamTimeout;
    private final boolean simplifyPiiTokens;

    /**
     * Creates output inspection with default limits and no observer callbacks.
     *
     * @param inspectionService inspectors and policies applied to the application output
     */
    public InspectionOutputAdvisor(InspectionService inspectionService) {
        this(inspectionService, InspectionLimits.defaults(), InspectionObserver.noop(),
                DEFAULT_MAX_FRAMES, DEFAULT_STREAM_TIMEOUT);
    }

    /**
     * Sets limits for output inspection and stream collection. Input inspection has separate limits.
     *
     * @param inspectionService inspectors and policies applied to the application output
     * @param inspectionLimits text extraction and inspection limits
     * @param inspectionObserver receives inspection reports and failures
     * @param maxFrames maximum response frames collected for inspection, including empty frames
     * @param streamTimeout total stream collection budget, including model calls and tool execution
     * @throws IllegalArgumentException if a stream budget is not positive or the timeout cannot fit in nanoseconds
     */
    public InspectionOutputAdvisor(InspectionService inspectionService, InspectionLimits inspectionLimits,
        InspectionObserver inspectionObserver, int maxFrames, Duration streamTimeout) {
        this.inspectionEnforcement = new InspectionEnforcement(inspectionService, inspectionLimits, inspectionObserver);
        this.inspectionLimits = inspectionLimits;
        if (maxFrames < 1) {
            throw new IllegalArgumentException("Maximum stream frame count must be positive");
        }
        if (streamTimeout == null || streamTimeout.isNegative() || streamTimeout.isZero()) {
            throw new IllegalArgumentException("Stream timeout must be positive");
        }
        try {
            streamTimeout.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Stream timeout must fit nanoseconds");
        }
        this.maxFrames = maxFrames;
        this.streamTimeout = streamTimeout;
        this.simplifyPiiTokens = inspectionService.requiresPiiTokenSimplifier();
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        validateChain(callAdvisorChain.getCallAdvisors());
        ChatClientRequest chatClientRequestWithPrivacyTracking =
                OutputResponseContext.requestPrivacyTracking(chatClientRequest, simplifyPiiTokens);
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(chatClientRequestWithPrivacyTracking);

        ContentSegment.PrivacyProcessingStatus privacyProcessingStatus =
                OutputResponseContext.isPrivacyProcessed(chatClientResponse)
                        ? ContentSegment.PrivacyProcessingStatus.PROCESSED
                        : ContentSegment.PrivacyProcessingStatus.UNKNOWN;
        Map<String, String> simplifiedTextsByOriginalText =
                OutputResponseContext.findSimplifiedTextsByOriginalText(chatClientResponse);

        inspectOutput(
            () -> extractOutputSegments(chatClientResponse, privacyProcessingStatus),
            simplifiedTextsByOriginalText, false);
        return OutputResponseContext.clearPrivacyTracking(chatClientResponse);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        return Flux.defer(() -> {
            validateChain(streamAdvisorChain.getStreamAdvisors());
            InspectionOutputBuffer inspectionOutputBuffer = new InspectionOutputBuffer(inspectionLimits, maxFrames);
            Mono<InspectionOutputBuffer> collectedOutput = streamAdvisorChain
                .nextStream(OutputResponseContext.requestPrivacyTracking(chatClientRequest, simplifyPiiTokens))
                .doOnNext(chatClientResponse -> {
                    try {
                        inspectionOutputBuffer.accept(chatClientResponse);
                    } catch (RuntimeException ex) {
                        throw inspectionEnforcement.failure(ex,
                            new InspectionExecution(InspectionExecution.Phase.FINAL_OUTPUT, true));
                    }
                })
                .then(Mono.just(inspectionOutputBuffer));
            Mono<InspectionOutputBuffer> timeoutFailure = Mono.defer(() -> {
                InspectionException timeoutException = new InspectionException(InspectionFailureCode.TIMEOUT);
                return Mono.error(inspectionEnforcement.failure(timeoutException,
                    new InspectionExecution(InspectionExecution.Phase.FINAL_OUTPUT, true)));
            });
            // Mono timeout bounds total assembly time, not merely the gap between frames.
            return collectedOutput
                    .timeout(streamTimeout, timeoutFailure)
                    .flatMapMany(this::inspectAndEmit);
        });
    }

    private List<ContentSegment> extractOutputSegments(ChatClientResponse chatClientResponse,
            ContentSegment.PrivacyProcessingStatus privacyProcessingStatus) {
        InspectionTextExtractor inspectionTextExtractor =
            new InspectionTextExtractor(inspectionLimits, privacyProcessingStatus, "output-");
        if (chatClientResponse.chatResponse() != null) {
            for (Generation generation : chatClientResponse.chatResponse().getResults()) {
                InspectionTextExtractor.requireSupportedAssistant(generation.getOutput());
                inspectionTextExtractor.addOutputPayload(generation.getOutput().getText(), privacyProcessingStatus);
            }
        }
        return inspectionTextExtractor.buildSegments();
    }

    private Flux<ChatClientResponse> inspectAndEmit(InspectionOutputBuffer inspectionOutputBuffer) {
        Mono<List<ChatClientResponse>> inspectedFrames = Mono.fromCallable(() -> {
            Map<String, String> simplifiedTextsByOriginalText = inspectionOutputBuffer.collectSimplifiedTextsByOriginalText();
            inspectOutput(inspectionOutputBuffer::buildSegments, simplifiedTextsByOriginalText, true);
            return inspectionOutputBuffer.framesWithoutPrivacyTracking();
        });
        return inspectedFrames
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    private void inspectOutput(Supplier<List<ContentSegment>> outputSegmentsSupplier,
        Map<String, String> simplifiedTextsByOriginalText, boolean streaming) {
        UnaryOperator<String> piiTokenSimplifier = null;
        if (simplifiedTextsByOriginalText != null) {
            piiTokenSimplifier = outputText -> simplifiedTextsByOriginalText.getOrDefault(outputText, outputText);
        }
        inspectionEnforcement.inspectAndEnforce(outputSegmentsSupplier, piiTokenSimplifier,
            new InspectionExecution(InspectionExecution.Phase.FINAL_OUTPUT, streaming));
    }

    private void validateChain(List<? extends Advisor> advisors) {
        int outputAdvisorIndex = advisors.indexOf(this);
        if (outputAdvisorIndex < 0) {
            throw new IllegalStateException("Output inspection advisor must be part of the advisor chain");
        }
        long outputAdvisorCount = advisors.stream().filter(InspectionOutputAdvisor.class::isInstance).count();
        if (outputAdvisorCount != 1) {
            throw new IllegalStateException("Exactly one output inspection advisor is required");
        }
        if (advisors.subList(0, outputAdvisorIndex).stream().anyMatch(ToolAdvisor.class::isInstance)) {
            throw new IllegalStateException("Output inspection advisor must precede tool advisors");
        }
    }

    @Override
    public String getName() {
        return "InspectionOutputAdvisor";
    }

    @Override
    public int getOrder() {
        return DEFAULT_ORDER;
    }
}
