package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.Set;

/**
 * Enforces opaque-token handling at its configured model request boundary. The default
 * order places it after application request advisors; applications remain responsible for
 * request mutations performed by advisors placed after it.
 */
public final class PrivacyModelBoundaryAdvisor implements CallAdvisor, StreamAdvisor {

    /** Tested terminal request position immediately before model execution. */
    public static final int DEFAULT_ORDER = Ordered.LOWEST_PRECEDENCE - 1;

    private final PrivacyService privacyService;
    private final PrivacyMessageTransformer messageTransformer;
    private final PrivacyModelControlValidator modelControlValidator;
    private final PrivacyToolCallbackFactory.Provenance requiredFactoryProvenance;
    private final int order;

    /**
     * Creates a service-bound model boundary at {@link #DEFAULT_ORDER}.
     *
     * @param privacyService service that owns request sessions and transformations
     */
    public PrivacyModelBoundaryAdvisor(PrivacyService privacyService) {
        this(privacyService, null, DEFAULT_ORDER);
    }

    /**
     * Creates a model boundary at an application-selected advisor order.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param order Spring AI advisor order
     */
    public PrivacyModelBoundaryAdvisor(PrivacyService privacyService, int order) {
        this(privacyService, null, order);
    }

    /**
     * Creates a default-order boundary that optionally requires callbacks from one exact factory.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param requiredFactory factory whose wrappers are accepted, or {@code null} to
     * accept wrappers from any factory using the same service
     */
    public PrivacyModelBoundaryAdvisor(
            PrivacyService privacyService,
            PrivacyToolCallbackFactory requiredFactory
    ) {
        this(privacyService, requiredFactory, DEFAULT_ORDER);
    }

    /**
     * Creates a factory-bound model boundary at an application-selected advisor order.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param requiredFactory factory whose wrappers are accepted, or {@code null} to
     * accept wrappers from any factory using the same service
     * @param order Spring AI advisor order
     */
    public PrivacyModelBoundaryAdvisor(
            PrivacyService privacyService,
            PrivacyToolCallbackFactory requiredFactory,
            int order
    ) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
        if (requiredFactory != null && !requiredFactory.usesPrivacyService(privacyService)) {
            throw new IllegalArgumentException("requiredFactory must use the same PrivacyService");
        }
        this.requiredFactoryProvenance = requiredFactory == null ? null : requiredFactory.provenance();
        this.messageTransformer = new PrivacyMessageTransformer(privacyService);
        this.modelControlValidator = new PrivacyModelControlValidator(privacyService);
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest boundaryRequest = prepareModelRequest(request);
        return chain.nextCall(boundaryRequest);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest boundaryRequest = prepareModelRequest(request);
        return chain.nextStream(boundaryRequest);
    }

    @Override
    public String getName() {
        return "PrivacyModelBoundaryAdvisor";
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    private ChatClientRequest prepareModelRequest(ChatClientRequest request) {
        PrivacyRequestContextSupport.requireLifecycle(request, "PrivacyModelBoundaryAdvisor");
        PrivacyContextHandle handle = PrivacyRequestContextSupport.findHandle(request)
                .orElseThrow(() -> new PrivacyGuardrailException(
                        PrivacyFailureCode.CONTEXT_REQUIRED,
                        PrivacyPhase.SESSION,
                        "PrivacyModelBoundaryAdvisor requires an active request privacy session"
                ));
        if (!this.privacyService.isSessionActive(handle)) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.CONTEXT_NOT_ACTIVE,
                    PrivacyPhase.SESSION,
                    "Privacy context is unknown or already closed"
            );
        }
        this.modelControlValidator.validateOutputFormatControlFields(handle, request);
        this.modelControlValidator.validateHistoryToolControlFields(handle, request);
        PrivacyToolExecutionContextSupport.requireCallbacksMatchValidatedSnapshot(request);
        Set<String> registeredToolNames = request.prompt().getOptions() instanceof ToolCallingChatOptions
                ? PrivacyToolContextAdvisor.requirePrivacyWrappedToolNames(
                        request,
                        this.privacyService,
                        this.requiredFactoryProvenance
                )
                : Set.of();
        this.modelControlValidator.validateModelVisibleToolDefinitions(handle, request);
        ChatClientRequest tokenized = this.messageTransformer.tokenize(handle, request);
        return PrivacyToolExecutionContextSupport.attachRegisteredToolNames(
                tokenized,
                registeredToolNames
        );
    }

}
