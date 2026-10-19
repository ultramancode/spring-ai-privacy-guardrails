package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundarySpec;
import io.github.ultramancode.springai.privacy.boundary.OutputResponseContext;
import io.github.ultramancode.springai.privacy.boundary.PiiTokenSimplifierContext;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Owns the privacy session around the advisors that execute inside its configured position
 * and applies the optional output policy when control returns to that position.
 * The default order wraps the standard privacy bundle. Applications remain responsible for
 * mutations performed by advisors placed outside it.
 */
public final class PrivacyLifecycleAdvisor implements CallAdvisor, StreamAdvisor {

    /** Default outermost position for the request privacy-session lifetime. */
    public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE;

    private final PrivacyService privacyService;
    private final int order;

    /**
     * Creates a lifecycle boundary at {@link #DEFAULT_ORDER}.
     *
     * @param privacyService service that opens and closes request privacy sessions
     */
    public PrivacyLifecycleAdvisor(PrivacyService privacyService) {
        this(privacyService, DEFAULT_ORDER);
    }

    /**
     * Creates a lifecycle boundary at an application-selected advisor order.
     *
     * @param privacyService service that opens and closes request privacy sessions
     * @param order Spring AI advisor order
     */
    public PrivacyLifecycleAdvisor(PrivacyService privacyService, int order) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        PrivacyOutputAdvisor outputAdvisor = validateBundleAndFindOutputAdvisor(chain.getCallAdvisors());
        try (PrivacySession privacySession = this.privacyService.openSession()) {
            ChatClientRequest activeRequest = PrivacyRequestContextSupport.attachLifecycle(
                    request,
                    privacySession.handle()
            );
            activeRequest = PiiTokenSimplifierContext.attach(activeRequest,
                    text -> this.privacyService.simplifyTokens(privacySession.handle(), text));
            ChatClientResponse response = chain.nextCall(activeRequest);
            if (outputAdvisor != null) {
                response = outputAdvisor.protectAtApplicationBoundary(privacySession.handle(), response);
            }
            boolean shouldMarkPrivacyProcessed = outputAdvisor != null
                    && OutputResponseContext.isPrivacyTrackingRequested(request);
            return prepareResponseContext(response, shouldMarkPrivacyProcessed, outputTokenSimplifier(request, privacySession));
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        PrivacyOutputAdvisor outputAdvisor = validateBundleAndFindOutputAdvisor(chain.getStreamAdvisors());
        return Flux.using(
                this.privacyService::openSession,
                privacySession -> {
                    ChatClientRequest activeRequest = PrivacyRequestContextSupport.attachLifecycle(
                            request,
                            privacySession.handle()
                    );
                    activeRequest = PiiTokenSimplifierContext.attach(activeRequest,
                            text -> this.privacyService.simplifyTokens(privacySession.handle(), text));
                    Flux<ChatClientResponse> responses = chain.nextStream(activeRequest);
                    if (outputAdvisor != null) {
                        responses = outputAdvisor.protectAtApplicationBoundary(privacySession.handle(), responses);
                    }
                    boolean shouldMarkPrivacyProcessed = outputAdvisor != null
                            && OutputResponseContext.isPrivacyTrackingRequested(request);
                    UnaryOperator<String> piiTokenSimplifier = outputTokenSimplifier(request, privacySession);
                    return responses.map(response -> prepareResponseContext(response, shouldMarkPrivacyProcessed, piiTokenSimplifier));
                },
                PrivacySession::close
        );
    }

    @Override
    public String getName() {
        return "PrivacyLifecycleAdvisor";
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    private ChatClientResponse prepareResponseContext(
            ChatClientResponse response,
            boolean shouldMarkPrivacyProcessed,
            UnaryOperator<String> piiTokenSimplifier
    ) {
        ChatClientResponse cleanedResponse = PrivacyRequestContextSupport.withoutInternalPrivacyContext(response);
        cleanedResponse = OutputResponseContext.clearPrivacyTracking(cleanedResponse);
        if (shouldMarkPrivacyProcessed) {
            return OutputResponseContext.markPrivacyProcessed(cleanedResponse, piiTokenSimplifier);
        }
        return cleanedResponse;
    }

    private UnaryOperator<String> outputTokenSimplifier(ChatClientRequest request, PrivacySession privacySession) {
        if (!OutputResponseContext.isPiiTokenSimplificationRequested(request)) {
            return null;
        }
        return text -> this.privacyService.simplifyTokens(privacySession.handle(), text);
    }

    private PrivacyOutputAdvisor validateBundleAndFindOutputAdvisor(List<? extends Advisor> advisors) {
        Objects.requireNonNull(advisors, "advisors must not be null");
        requireExactlyOne(advisors, PrivacyLifecycleAdvisor.class);
        requireExactlyOne(advisors, PrivacyInputAdvisor.class);
        requireExactlyOne(advisors, PrivacyToolContextAdvisor.class);
        requireExactlyOne(advisors, PrivacyToolCallValidationAdvisor.class);
        requireExactlyOneModelStage(advisors);
        List<PrivacyOutputAdvisor> outputAdvisors = advisors.stream()
                .filter(PrivacyOutputAdvisor.class::isInstance)
                .map(PrivacyOutputAdvisor.class::cast)
                .toList();
        if (outputAdvisors.size() > 1) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    PrivacyPhase.OUTPUT_POLICY,
                    "PrivacyLifecycleAdvisor detected multiple output policy advisors"
            );
        }
        return outputAdvisors.isEmpty() ? null : outputAdvisors.get(0);
    }

    private void requireExactlyOneModelStage(List<? extends Advisor> advisors) {
        long count = advisors.stream()
                .filter(advisor -> ModelRequestBoundarySpec.containsStageType(advisor, PrivacyModelRequestStage.class))
                .count();
        if (count != 1) {
            throw new PrivacyGuardrailException(PrivacyFailureCode.TRANSFORMATION_CONFLICT, PrivacyPhase.SESSION,
                    "PrivacyLifecycleAdvisor requires exactly one managed privacy model stage");
        }
    }

    private void requireExactlyOne(
            List<? extends Advisor> advisors,
            Class<? extends Advisor> requiredType
    ) {
        long count = advisors.stream().filter(requiredType::isInstance).count();
        if (count != 1) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    PrivacyPhase.SESSION,
                    "PrivacyLifecycleAdvisor requires exactly one complete mandatory privacy advisor set"
            );
        }
    }
}
