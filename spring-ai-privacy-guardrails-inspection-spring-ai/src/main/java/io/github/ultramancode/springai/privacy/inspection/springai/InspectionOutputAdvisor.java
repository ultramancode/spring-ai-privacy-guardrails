package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.PrivacyOutputProcessing;
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
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

/**
 * Allows or blocks final assistant text delivered to the application, including returnDirect results.
 * Provider-specific reasoning fields, tool-call arguments and metadata are outside this scope.
 *
 * <p>Runs after configured privacy output processing. Output is PROCESSED only when that
 * processing completed for the same text, otherwise UNKNOWN.
 *
 * <p>Streams buffer through completion and release only the inspected final round.
 * A block, assembly failure or cancellation releases no buffered content.
 * Frame and assembly-time limits cover the complete tool loop, including tool execution.
 */
public final class InspectionOutputAdvisor implements CallAdvisor, StreamAdvisor, PriorityOrdered {
    public static final int DEFAULT_MAX_FRAMES = 4096;
    public static final Duration DEFAULT_STREAM_TIMEOUT = Duration.ofSeconds(60);
    public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    private final InspectionEnforcement enforcement;
    private final InspectionLimits limits;
    private final int maxFrames;
    private final Duration streamTimeout;

    public InspectionOutputAdvisor(InspectionService service) {
        this(service, InspectionLimits.defaults(), InspectionObserver.noop(),
                DEFAULT_MAX_FRAMES, DEFAULT_STREAM_TIMEOUT);
    }

    /** Input and output inspection budgets are independent. Stream assembly includes tool execution time. */
    public InspectionOutputAdvisor(InspectionService service, InspectionLimits limits,
            InspectionObserver observer, int maxFrames, Duration streamTimeout) {
        this.enforcement = new InspectionEnforcement(service, limits, observer);
        this.limits = limits;
        if (maxFrames < 1 || streamTimeout == null || streamTimeout.isNegative() || streamTimeout.isZero()) {
            throw new IllegalArgumentException("Positive stream frame and duration budgets are required");
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
        ChatClientResponse response = chain.nextCall(PrivacyOutputProcessing.requestCompletion(request));
        ContentSegment.PrivacyProcessingStatus status = PrivacyOutputProcessing.hasCompleted(response)
                ? ContentSegment.PrivacyProcessingStatus.PROCESSED : ContentSegment.PrivacyProcessingStatus.UNKNOWN;
        enforcement.inspect(() -> {
            InspectionTextExtractor extractor = new InspectionTextExtractor(limits, status, "output-");
            if (response.chatResponse() != null) {
                for (var generation : response.chatResponse().getResults()) {
                    InspectionTextExtractor.requireSupportedAssistant(generation.getOutput());
                    extractor.outputPayload(generation.getOutput().getText(), status);
                }
            }
            return extractor.segments();
        });
        return PrivacyOutputProcessing.clear(response);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            validateChain(chain.getStreamAdvisors());
            var buffer = new InspectionOutputBuffer(limits, maxFrames);
            StreamAdvisorChain selected = InspectionFinalOutputStream.prepare(chain, this, limits, maxFrames,
                    enforcement);
            return selected.nextStream(PrivacyOutputProcessing.requestCompletion(request))
                    .doOnNext(response -> {
                        try {
                            buffer.accept(response);
                        } catch (RuntimeException ex) {
                            throw enforcement.failure(ex);
                        }
                    })
                    .then(Mono.just(buffer))
                    // Mono timeout bounds total assembly time, not merely the gap between frames.
                    .timeout(streamTimeout, Mono.defer(() -> Mono.error(enforcement.failure(
                            new InspectionException(InspectionFailureCode.TIMEOUT)))))
                    .flatMapMany(completed -> Mono.fromCallable(() -> {
                        enforcement.inspect(completed::segments);
                        return completed.frames();
                    }).subscribeOn(Schedulers.boundedElastic()).flatMapMany(Flux::fromIterable));
        });
    }

    private void validateChain(List<? extends Advisor> advisors) {
        int index = advisors.indexOf(this);
        if (index < 0 || advisors.stream().filter(InspectionOutputAdvisor.class::isInstance).count() != 1
                || advisors.subList(0, index).stream().anyMatch(ToolAdvisor.class::isInstance)) {
            throw new IllegalStateException("Exactly one output inspection advisor is required and must precede tool advisors");
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
