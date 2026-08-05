package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Validates model responses that pass through this advisor before tool execution. The
 * default order places it immediately inside {@link ToolCallingAdvisor}; applications
 * using a custom order own the placement and any response mutations performed after it.
 * When an outer {@link PrivacyOutputAdvisor} supplies response-inspection limits, the
 * default constructors adopt them. Limits explicitly supplied to this validator must
 * match the outer output limits for requests that register protected tools.
 */
public final class PrivacyToolCallValidationAdvisor implements CallAdvisor, StreamAdvisor {

    /** Tested position immediately inside Spring AI tool calling. */
    public static final int DEFAULT_ORDER = ToolCallingAdvisor.DEFAULT_ORDER + 1;

    private final PrivacyService privacyService;
    private final PrivacyModelControlValidator modelControlValidator;
    private final PrivacyResponseInspectionLimits responseInspectionLimits;
    private final boolean usesExplicitResponseInspectionLimits;
    private final int order;

    /**
     * Creates a tool-call validator with default response-inspection limits and order.
     *
     * @param privacyService service that owns request sessions and transformations
     */
    public PrivacyToolCallValidationAdvisor(PrivacyService privacyService) {
        this(privacyService, PrivacyResponseInspectionLimits.defaults(), false, DEFAULT_ORDER);
    }

    /**
     * Creates a validator at an application-selected order. Placing it immediately after
     * the matching {@code ToolCallingAdvisor} is the recommended standard layout.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param order Spring AI advisor order
     */
    public PrivacyToolCallValidationAdvisor(PrivacyService privacyService, int order) {
        this(privacyService, PrivacyResponseInspectionLimits.defaults(), false, order);
    }

    /**
     * Creates a validator with application-selected limits for responses that can
     * reach Spring AI tool execution. Calls without registered tools are not
     * subject to these response-inspection limits. If an outer output advisor also
     * supplies response-inspection limits, both values must match.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param responseInspectionLimits limits applied while a response can reach tool execution
     */
    public PrivacyToolCallValidationAdvisor(
            PrivacyService privacyService,
            PrivacyResponseInspectionLimits responseInspectionLimits
    ) {
        this(privacyService, responseInspectionLimits, true, DEFAULT_ORDER);
    }

    /**
     * Creates a validator with application-selected execution limits and order. If an
     * outer output advisor also supplies response-inspection limits, both values must match.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param responseInspectionLimits limits applied while a response can reach tool execution
     * @param order Spring AI advisor order
     */
    public PrivacyToolCallValidationAdvisor(
            PrivacyService privacyService,
            PrivacyResponseInspectionLimits responseInspectionLimits,
            int order
    ) {
        this(privacyService, responseInspectionLimits, true, order);
    }

    private PrivacyToolCallValidationAdvisor(
            PrivacyService privacyService,
            PrivacyResponseInspectionLimits responseInspectionLimits,
            boolean usesExplicitResponseInspectionLimits,
            int order
    ) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
        this.responseInspectionLimits = Objects.requireNonNull(
                responseInspectionLimits,
                "responseInspectionLimits must not be null"
        );
        this.usesExplicitResponseInspectionLimits = usesExplicitResponseInspectionLimits;
        this.order = order;
        this.modelControlValidator = new PrivacyModelControlValidator(privacyService);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        PrivacyContextHandle handle = requireActiveHandle(request);
        boolean toolExecutionPossible = hasRegisteredTools(request);
        PrivacyResponseInspectionLimits limits = toolExecutionPossible
                ? resolveExecutionLimits(request)
                : this.responseInspectionLimits;
        ChatClientResponse response = chain.nextCall(request);
        if (toolExecutionPossible) {
            new PrivacyResponseInspectionGuard(limits, PrivacyPhase.TOOL_INPUT, true).accept(response);
        }
        ChatClientResponse validated = validateResponse(handle, response);
        return validated;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        PrivacyContextHandle handle = requireActiveHandle(request);
        boolean toolExecutionPossible = hasRegisteredTools(request);
        PrivacyResponseInspectionLimits limits = toolExecutionPossible
                ? resolveExecutionLimits(request)
                : this.responseInspectionLimits;
        Flux<ChatClientResponse> responses = chain.nextStream(request);
        if (!toolExecutionPossible) {
            return responses.map(response -> validateResponse(handle, response));
        }
        return Flux.defer(() -> {
            PrivacyResponseInspectionGuard guard = new PrivacyResponseInspectionGuard(
                    limits,
                    PrivacyPhase.TOOL_INPUT,
                    true
            );
            return responses
                    .timeout(limits.streamIdleTimeout(), Flux.error(streamTimeout()))
                    .map(response -> {
                        guard.accept(response);
                        return validateResponse(handle, response);
                    });
        });
    }

    private boolean hasRegisteredTools(ChatClientRequest request) {
        return !PrivacyToolContextAdvisor.requirePrivacyWrappedToolNames(
                request,
                this.privacyService,
                null
        ).isEmpty();
    }

    private PrivacyResponseInspectionLimits resolveExecutionLimits(ChatClientRequest request) {
        Optional<PrivacyResponseInspectionLimits> outputLimits =
                PrivacyOutputContextSupport.findResponseInspectionLimits(request);
        if (outputLimits.isEmpty()) {
            return this.responseInspectionLimits;
        }
        PrivacyResponseInspectionLimits sharedLimits = outputLimits.get();
        if (this.usesExplicitResponseInspectionLimits
                && !this.responseInspectionLimits.equals(sharedLimits)) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    PrivacyPhase.TOOL_INPUT,
                    "Conflicting response inspection limits are configured for tool execution"
            );
        }
        return sharedLimits;
    }

    @Override
    public String getName() {
        return "PrivacyToolCallValidationAdvisor";
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    private ChatClientResponse validateResponse(
            PrivacyContextHandle expectedHandle,
            ChatClientResponse response
    ) {
        Objects.requireNonNull(response, "response must not be null");
        PrivacyContextHandle responseHandle = PrivacyRequestContextSupport.findHandle(response.context())
                .orElseThrow(() -> missingValidatedContext());
        if (responseHandle != expectedHandle || !this.privacyService.isSessionActive(responseHandle)) {
            throw missingValidatedContext();
        }
        Set<String> registeredToolNames = PrivacyToolExecutionContextSupport.requireRegisteredToolNames(response);
        this.modelControlValidator.validateResponseToolCalls(
                responseHandle,
                response,
                registeredToolNames
        );
        return response;
    }

    private PrivacyContextHandle requireActiveHandle(ChatClientRequest request) {
        PrivacyRequestContextSupport.requireLifecycle(request, "PrivacyToolCallValidationAdvisor");
        PrivacyContextHandle handle = PrivacyRequestContextSupport.findHandle(request)
                .orElseThrow(this::missingValidatedContext);
        if (!this.privacyService.isSessionActive(handle)) {
            throw missingValidatedContext();
        }
        return handle;
    }

    private PrivacyGuardrailException missingValidatedContext() {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.CONTEXT_REQUIRED,
                PrivacyPhase.TOOL_INPUT,
                "Tool execution response is missing validated privacy metadata"
        );
    }

    private PrivacyGuardrailException streamTimeout() {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.STREAM_TIMEOUT,
                PrivacyPhase.TOOL_INPUT,
                "Streaming model response exceeded the configured privacy idle timeout"
        );
    }
}
