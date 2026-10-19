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
 * Supplies input inspection with a function that simplifies PII tokens using the current session.
 * When output inspection requests token simplification, prepares its text before the session closes.
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
    public ChatClientResponse adviseCall(
            ChatClientRequest chatClientRequest,
            CallAdvisorChain callAdvisorChain
    ) {
        PrivacyOutputAdvisor privacyOutputAdvisor =
                validateBundleAndFindOutputAdvisor(callAdvisorChain.getCallAdvisors());
        try (PrivacySession privacySession = this.privacyService.openSession()) {
            ChatClientRequest chatClientRequestWithPrivacyContext =
                    prepareRequestContext(chatClientRequest, privacySession);
            ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(chatClientRequestWithPrivacyContext);
            if (privacyOutputAdvisor != null) {
                chatClientResponse = privacyOutputAdvisor.protectAtApplicationBoundary(
                        privacySession.handle(),
                        chatClientResponse
                );
            }
            boolean shouldMarkPrivacyProcessed = privacyOutputAdvisor != null
                    && OutputResponseContext.isPrivacyTrackingRequested(chatClientRequest);
            UnaryOperator<String> outputPiiTokenSimplifier =
                    createOutputTokenSimplifierIfRequested(chatClientRequest, privacySession);
            return prepareResponseContext(chatClientResponse, shouldMarkPrivacyProcessed, outputPiiTokenSimplifier);
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain
    ) {
        PrivacyOutputAdvisor privacyOutputAdvisor =
                validateBundleAndFindOutputAdvisor(streamAdvisorChain.getStreamAdvisors());
        return Flux.using(
                this.privacyService::openSession,
                privacySession -> {
                    ChatClientRequest chatClientRequestWithPrivacyContext =
                            prepareRequestContext(chatClientRequest, privacySession);
                    Flux<ChatClientResponse> chatClientResponses =
                            streamAdvisorChain.nextStream(chatClientRequestWithPrivacyContext);
                    if (privacyOutputAdvisor != null) {
                        chatClientResponses = privacyOutputAdvisor.protectAtApplicationBoundary(
                                privacySession.handle(),
                                chatClientResponses
                        );
                    }
                    boolean shouldMarkPrivacyProcessed = privacyOutputAdvisor != null
                            && OutputResponseContext.isPrivacyTrackingRequested(chatClientRequest);
                    UnaryOperator<String> outputPiiTokenSimplifier =
                            createOutputTokenSimplifierIfRequested(chatClientRequest, privacySession);
                    return chatClientResponses.map(chatClientResponse -> prepareResponseContext(
                            chatClientResponse,
                            shouldMarkPrivacyProcessed,
                            outputPiiTokenSimplifier
                    ));
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

    /**
     * Returns a new request containing the supplied session handle and input PII token simplifier.
     * The simplifier uses this session and must be called before the session closes.
     */
    private ChatClientRequest prepareRequestContext(
            ChatClientRequest chatClientRequest,
            PrivacySession privacySession
    ) {
        ChatClientRequest chatClientRequestWithPrivacyContext = PrivacyRequestContextSupport.attachLifecycle(
                chatClientRequest,
                privacySession.handle()
        );
        UnaryOperator<String> inputPiiTokenSimplifier =
                text -> this.privacyService.simplifyTokens(privacySession.handle(), text);
        return PiiTokenSimplifierContext.attach(chatClientRequestWithPrivacyContext, inputPiiTokenSimplifier);
    }

    /**
     * Removes internal Privacy context and attaches processing results requested by output inspection.
     * Runs before the privacy session closes so inspection text can be prepared using its simplifier.
     */
    private ChatClientResponse prepareResponseContext(
            ChatClientResponse chatClientResponse,
            boolean shouldMarkPrivacyProcessed,
            UnaryOperator<String> outputPiiTokenSimplifier
    ) {
        ChatClientResponse cleanedChatClientResponse =
                PrivacyRequestContextSupport.withoutInternalPrivacyContext(chatClientResponse);
        cleanedChatClientResponse = OutputResponseContext.clearPrivacyTracking(cleanedChatClientResponse);
        if (shouldMarkPrivacyProcessed) {
            return OutputResponseContext.markPrivacyProcessed(cleanedChatClientResponse, outputPiiTokenSimplifier);
        }
        return cleanedChatClientResponse;
    }

    /**
     * Creates a simplifier for output inspection when the request asks for PII token simplification.
     * The returned function must be used while the supplied privacy session is active.
     *
     * @return the simplifier function, or {@code null} when output token simplification was not requested
     */
    private UnaryOperator<String> createOutputTokenSimplifierIfRequested(
            ChatClientRequest chatClientRequest,
            PrivacySession privacySession
    ) {
        if (!OutputResponseContext.isPiiTokenSimplificationRequested(chatClientRequest)) {
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
