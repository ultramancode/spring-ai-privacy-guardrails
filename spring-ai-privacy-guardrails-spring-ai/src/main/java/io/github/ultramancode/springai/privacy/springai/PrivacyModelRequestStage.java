package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Protects the model request before tool-definition authorization and content inspection. */
final class PrivacyModelRequestStage implements UnaryOperator<ChatClientRequest> {

    static final String MODEL_CONTENT_PROTECTION = "io.github.ultramancode.springai.privacy.model-content-protection";

    /**
     * Whether the current message list passed this privacy stage. This is provenance of
     * the configured privacy transformation, not a guarantee that every PII was detected.
     * Optional downstream integrations can use it without retaining the privacy session.
     */
    public static boolean isModelContentProtected(ChatClientRequest request) {
        Object marker = request.context().get(MODEL_CONTENT_PROTECTION);
        return marker instanceof ModelContentProtection protection
                && protection.messages().equals(request.prompt().getInstructions());
    }

    private record ModelContentProtection(List<Message> messages) {
        private ModelContentProtection {
            messages = List.copyOf(messages);
        }

        @Override
        public String toString() {
            return "ModelContentProtection[content=<redacted>]";
        }
    }

    private final PrivacyService privacyService;
    private final PrivacyMessageTransformer messageTransformer;
    private final PrivacyModelControlValidator modelControlValidator;
    private final PrivacyToolCallbackFactory.Provenance requiredFactoryProvenance;
    private final PrivacyEnforcementNotifier enforcementNotifier;

    PrivacyModelRequestStage(PrivacyService privacyService, PrivacyToolCallbackFactory requiredFactory,
            PrivacyEnforcementObserver enforcementObserver) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
        if (requiredFactory != null && !requiredFactory.usesPrivacyService(privacyService)) {
            throw new IllegalArgumentException("requiredFactory must use the same PrivacyService");
        }
        this.requiredFactoryProvenance = requiredFactory == null ? null : requiredFactory.provenance();
        this.messageTransformer = new PrivacyMessageTransformer(privacyService);
        this.modelControlValidator = new PrivacyModelControlValidator(privacyService);
        this.enforcementNotifier = new PrivacyEnforcementNotifier(enforcementObserver);
    }

    /** Applies privacy validation and transformation as the first managed model request phase. */
    @Override
    public ChatClientRequest apply(ChatClientRequest request) {
        PrivacyRequestContextSupport.requireLifecycle(request, "PrivacyModelRequestStage");
        PrivacyContextHandle handle = PrivacyRequestContextSupport.findHandle(request)
                .orElseThrow(() -> new PrivacyGuardrailException(
                        PrivacyFailureCode.CONTEXT_REQUIRED,
                        PrivacyPhase.SESSION,
                        "PrivacyModelRequestStage requires an active request privacy session"
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
        ChatClientRequest protectedRequest = PrivacyToolExecutionContextSupport.attachRegisteredToolNames(
                tokenized,
                registeredToolNames
        );
        this.enforcementNotifier.notify(
                PrivacyEnforcementBoundary.MODEL,
                PrivacyEnforcementOutcome.PROTECTED
        );
        Map<String, Object> context = new HashMap<>(protectedRequest.context());
        context.put(MODEL_CONTENT_PROTECTION, new ModelContentProtection(protectedRequest.prompt().getInstructions()));
        return protectedRequest.mutate().context(context).build();
    }

}
