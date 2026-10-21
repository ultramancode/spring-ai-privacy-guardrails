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
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Opens a privacy session before delegating to the remaining advisor chain.
 * Each call or stream subscription uses its own session, which is closed on
 * completion, failure, or stream cancellation.
 *
 * <p>Automatic tool callback wrapping can be enabled through
 * {@link PrivacyChatClientConfigurer.Builder#autoWrapToolCallbacks(boolean)}.
 * When a {@link PrivacyOutputAdvisor} is present, applies its output protection
 * before the session closes.</p>
 *
 * <p>At {@link #DEFAULT_ORDER}, the session opens before the other standard privacy
 * advisors run and remains active until their processing completes.
 * When using a custom order, place this advisor before advisors that require the session.</p>
 */
public final class PrivacyLifecycleAdvisor implements CallAdvisor, StreamAdvisor {

    /** Default outermost position for the request privacy-session lifetime. */
    public static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE;

    private final PrivacyService privacyService;
    private final PrivacyToolCallbackFactory autoWrappingToolCallbackFactory;
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
        this.autoWrappingToolCallbackFactory = null;
        this.order = order;
    }

    PrivacyLifecycleAdvisor(PrivacyService privacyService,
        PrivacyToolCallbackFactory autoWrappingToolCallbackFactory) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService");
        this.autoWrappingToolCallbackFactory = autoWrappingToolCallbackFactory;
        this.order = DEFAULT_ORDER;
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
            ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(
                wrapToolCallbacks(chatClientRequestWithPrivacyContext));
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
                        streamAdvisorChain.nextStream(wrapToolCallbacks(chatClientRequestWithPrivacyContext));
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

    private ChatClientRequest wrapToolCallbacks(ChatClientRequest chatClientRequest) {
        if (autoWrappingToolCallbackFactory == null) {
            return chatClientRequest;
        }

        Prompt originalPrompt = chatClientRequest.prompt();
        if (!(originalPrompt.getOptions() instanceof ToolCallingChatOptions toolCallingOptions)) {
            return chatClientRequest;
        }

        List<ToolCallback> toolCallbacks = toolCallingOptions.getToolCallbacks();
        if (toolCallbacks == null || toolCallbacks.isEmpty()) {
            return chatClientRequest;
        }

        // Authorization must capture the same protected callbacks that the tool loop executes.
        List<ToolCallback> protectedToolCallbacks =
            autoWrappingToolCallbackFactory.prepareCallbacks(toolCallbacks);
        ToolCallingChatOptions protectedToolCallingOptions = toolCallingOptions.mutate()
            .toolCallbacks(protectedToolCallbacks)
            .build();
        Prompt promptWithProtectedToolCallbacks =
            new Prompt(originalPrompt.getInstructions(), protectedToolCallingOptions);

        return chatClientRequest.mutate()
            .prompt(promptWithProtectedToolCallbacks)
            .build();
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
