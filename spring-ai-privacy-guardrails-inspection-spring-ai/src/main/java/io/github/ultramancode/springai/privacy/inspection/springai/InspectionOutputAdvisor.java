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

    private final InspectionEnforcement enforcement;
    private final InspectionLimits limits;
    private final int maxFrames;
    private final Duration streamTimeout;

    /**
     * Creates output inspection with default limits and no observer callbacks.
     *
     * @param service inspectors and policies applied to the application output
     */
    public InspectionOutputAdvisor(InspectionService service) {
        this(service, InspectionLimits.defaults(), InspectionObserver.noop(),
                DEFAULT_MAX_FRAMES, DEFAULT_STREAM_TIMEOUT);
    }

    /**
     * Sets limits for output inspection and stream collection. Input inspection has separate limits.
     *
     * @param service inspectors and policies applied to the application output
     * @param limits text extraction and inspection limits
     * @param observer receives inspection reports and failures
     * @param maxFrames maximum response frames collected for inspection, including empty frames
     * @param streamTimeout total stream collection budget, including model calls and tool execution
     * @throws IllegalArgumentException if a stream budget is not positive or the timeout cannot fit in nanoseconds
     */
    public InspectionOutputAdvisor(InspectionService service, InspectionLimits limits,
            InspectionObserver observer, int maxFrames, Duration streamTimeout) {
        this.enforcement = new InspectionEnforcement(service, limits, observer);
        this.limits = limits;
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
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        validateChain(chain.getCallAdvisors());
        ChatClientResponse response = chain.nextCall(OutputResponseContext.requestPrivacyTracking(request));
        ContentSegment.PrivacyProcessingStatus status = OutputResponseContext.isPrivacyProcessed(response)
                ? ContentSegment.PrivacyProcessingStatus.PROCESSED : ContentSegment.PrivacyProcessingStatus.UNKNOWN;
        enforcement.inspect(() -> {
            InspectionTextExtractor extractor = new InspectionTextExtractor(limits, status, "output-");
            if (response.chatResponse() != null) {
                for (Generation generation : response.chatResponse().getResults()) {
                    InspectionTextExtractor.requireSupportedAssistant(generation.getOutput());
                    extractor.addOutputPayload(generation.getOutput().getText(), status);
                }
            }
            return extractor.buildSegments();
        });
        return OutputResponseContext.clearPrivacyTracking(response);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            validateChain(chain.getStreamAdvisors());
            InspectionOutputBuffer buffer = new InspectionOutputBuffer(limits, maxFrames);
            Mono<InspectionOutputBuffer> collectedOutput = chain
                    .nextStream(OutputResponseContext.requestPrivacyTracking(request))
                    .doOnNext(response -> {
                        try {
                            buffer.accept(response);
                        } catch (RuntimeException ex) {
                            throw enforcement.failure(ex);
                        }
                    })
                    .then(Mono.just(buffer));
            Mono<InspectionOutputBuffer> timeoutFailure = Mono.defer(() -> {
                InspectionException timeout = new InspectionException(InspectionFailureCode.TIMEOUT);
                return Mono.error(enforcement.failure(timeout));
            });
            // Mono timeout bounds total assembly time, not merely the gap between frames.
            return collectedOutput
                    .timeout(streamTimeout, timeoutFailure)
                    .flatMapMany(this::inspectAndEmit);
        });
    }

    private Flux<ChatClientResponse> inspectAndEmit(InspectionOutputBuffer buffer) {
        Mono<List<ChatClientResponse>> inspectedFrames = Mono.fromCallable(() -> {
            enforcement.inspect(buffer::buildSegments);
            return buffer.framesWithoutPrivacyTracking();
        });
        return inspectedFrames
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
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
