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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolExecutionResult;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Configures application-facing output protection. The {@link PrivacyLifecycleAdvisor}
 * owns the single response-protection pass at the point where control returns to the
 * lifecycle advisor. Return-direct tool generations are identified from Spring AI's
 * final generation metadata, restored internally, and then subjected to the configured
 * output action. The default order is part of the tested standard layout; applications
 * using custom advisor composition own any response mutations performed outside the
 * lifecycle boundary.
 */
public final class PrivacyOutputAdvisor implements CallAdvisor, StreamAdvisor {

    /** Default action for sensitive application-facing output. */
    public static final PrivacyOutputAction DEFAULT_ACTION = PrivacyOutputAction.TOKENIZE;

    /** Default non-sensitive message used when output is blocked. */
    public static final String DEFAULT_BLOCK_EXCEPTION_MESSAGE = "Response blocked by privacy guardrail.";

    /** Tested position immediately outside Spring AI tool calling. */
    public static final int DEFAULT_ORDER = ToolCallingAdvisor.DEFAULT_ORDER - 2;

    private final PrivacyService privacyService;
    private final PrivacyOutputAction action;
    private final String blockExceptionMessage;
    private final PrivacyResponseInspectionLimits responseInspectionLimits;
    private final PrivacyModelControlValidator modelControlValidator;
    private final PrivacyEnforcementNotifier enforcementNotifier;
    private final int order;

    /**
     * Creates a tokenizing output boundary with default limits and order.
     *
     * @param privacyService service that owns request sessions and transformations
     */
    public PrivacyOutputAdvisor(PrivacyService privacyService) {
        this(
                privacyService,
                DEFAULT_ACTION,
                DEFAULT_BLOCK_EXCEPTION_MESSAGE,
                PrivacyResponseInspectionLimits.defaults(),
                PrivacyEnforcementObserver.noop(),
                DEFAULT_ORDER
        );
    }

    /**
     * Creates an output boundary with default response-inspection limits and order.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param action action applied when sensitive output is detected
     * @param blockExceptionMessage non-sensitive message used by {@link PrivacyOutputAction#BLOCK}
     */
    public PrivacyOutputAdvisor(PrivacyService privacyService, PrivacyOutputAction action, String blockExceptionMessage) {
        this(
                privacyService,
                action,
                blockExceptionMessage,
                PrivacyResponseInspectionLimits.defaults(),
                PrivacyEnforcementObserver.noop(),
                DEFAULT_ORDER
        );
    }

    /**
     * Creates an output boundary with application-selected response-inspection limits.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param action action applied when sensitive output is detected
     * @param blockExceptionMessage non-sensitive message used by {@link PrivacyOutputAction#BLOCK}
     * @param responseInspectionLimits hard limits applied while inspecting call and stream responses
     */
    public PrivacyOutputAdvisor(
            PrivacyService privacyService,
            PrivacyOutputAction action,
            String blockExceptionMessage,
            PrivacyResponseInspectionLimits responseInspectionLimits
    ) {
        this(
                privacyService,
                action,
                blockExceptionMessage,
                responseInspectionLimits,
                PrivacyEnforcementObserver.noop(),
                DEFAULT_ORDER
        );
    }

    /**
     * Creates an output boundary at an application-selected advisor order.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param action action applied when sensitive output is detected
     * @param blockExceptionMessage non-sensitive message used by {@link PrivacyOutputAction#BLOCK}
     * @param responseInspectionLimits hard limits applied while inspecting call and stream responses
     * @param order Spring AI advisor order
     */
    public PrivacyOutputAdvisor(
            PrivacyService privacyService,
            PrivacyOutputAction action,
            String blockExceptionMessage,
            PrivacyResponseInspectionLimits responseInspectionLimits,
            int order
    ) {
        this(
                privacyService,
                action,
                blockExceptionMessage,
                responseInspectionLimits,
                PrivacyEnforcementObserver.noop(),
                order
        );
    }

    /**
     * Creates an output boundary with an optional privacy-safe observer and default order.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param action action applied when sensitive output is detected
     * @param blockExceptionMessage non-sensitive message used by {@link PrivacyOutputAction#BLOCK}
     * @param responseInspectionLimits hard limits applied while inspecting call and stream responses
     * @param enforcementObserver observer for boundary and outcome events only
     */
    public PrivacyOutputAdvisor(
            PrivacyService privacyService,
            PrivacyOutputAction action,
            String blockExceptionMessage,
            PrivacyResponseInspectionLimits responseInspectionLimits,
            PrivacyEnforcementObserver enforcementObserver
    ) {
        this(
                privacyService,
                action,
                blockExceptionMessage,
                responseInspectionLimits,
                enforcementObserver,
                DEFAULT_ORDER
        );
    }

    /**
     * Creates an output boundary with an optional privacy-safe observer at a selected order.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param action action applied when sensitive output is detected
     * @param blockExceptionMessage non-sensitive message used by {@link PrivacyOutputAction#BLOCK}
     * @param responseInspectionLimits hard limits applied while inspecting call and stream responses
     * @param enforcementObserver observer for boundary and outcome events only
     * @param order Spring AI advisor order
     */
    public PrivacyOutputAdvisor(
            PrivacyService privacyService,
            PrivacyOutputAction action,
            String blockExceptionMessage,
            PrivacyResponseInspectionLimits responseInspectionLimits,
            PrivacyEnforcementObserver enforcementObserver,
            int order
    ) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
        this.action = Objects.requireNonNull(action, "action must not be null");
        if (blockExceptionMessage == null || blockExceptionMessage.isBlank()) {
            throw new IllegalArgumentException("blockExceptionMessage must not be blank");
        }
        this.blockExceptionMessage = blockExceptionMessage;
        this.responseInspectionLimits = Objects.requireNonNull(
                responseInspectionLimits,
                "responseInspectionLimits must not be null"
        );
        this.modelControlValidator = new PrivacyModelControlValidator(privacyService);
        this.enforcementNotifier = new PrivacyEnforcementNotifier(enforcementObserver);
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        PrivacyContextHandle handle = requireActiveHandle(request);
        return chain.nextCall(prepareOutputBoundaryRequest(request, handle));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        PrivacyContextHandle handle = requireActiveHandle(request);
        return chain.nextStream(prepareOutputBoundaryRequest(request, handle));
    }

    ChatClientResponse protectAtApplicationBoundary(
            PrivacyContextHandle handle,
            ChatClientResponse response
    ) {
        requireActiveHandle(handle);
        Objects.requireNonNull(response, "response must not be null");
        new PrivacyResponseInspectionGuard(this.responseInspectionLimits).accept(response);
        ChatResponse originalChatResponse = response.chatResponse();
        if (originalChatResponse == null || originalChatResponse.getResults().isEmpty()) {
            notifyApplicationOutput(PrivacyEnforcementOutcome.PROTECTED);
            return response;
        }

        List<Generation> generations = new ArrayList<>(originalChatResponse.getResults().size());
        boolean changed = false;
        for (Generation generation : originalChatResponse.getResults()) {
            Generation protectedGeneration = protectGeneration(handle, generation);
            generations.add(protectedGeneration);
            changed = changed || protectedGeneration != generation;
        }

        ChatClientResponse protectedResponse = response;
        if (changed) {
            ChatResponse chatResponse = new ChatResponse(
                    generations,
                    originalChatResponse.getMetadata()
            );
            protectedResponse = response.mutate().chatResponse(chatResponse).build();
        }
        notifyApplicationOutput(PrivacyEnforcementOutcome.PROTECTED);
        return protectedResponse;
    }

    Flux<ChatClientResponse> protectAtApplicationBoundary(
            PrivacyContextHandle handle,
            Flux<ChatClientResponse> responses
    ) {
        requireActiveHandle(handle);
        return bufferAndProtect(handle, responses);
    }

    @Override
    public String getName() {
        return "PrivacyOutputAdvisor";
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    private PrivacyContextHandle requireActiveHandle(ChatClientRequest request) {
        PrivacyContextHandle handle = PrivacyRequestContextSupport.findHandle(request)
                .orElseThrow(() -> new PrivacyGuardrailException(
                        PrivacyFailureCode.CONTEXT_REQUIRED,
                        PrivacyPhase.SESSION,
                        "PrivacyOutputAdvisor requires an active request privacy session"
                ));
        requireActiveHandle(handle);
        return handle;
    }

    private void requireActiveHandle(PrivacyContextHandle handle) {
        Objects.requireNonNull(handle, "handle must not be null");
        if (!this.privacyService.isSessionActive(handle)) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.CONTEXT_NOT_ACTIVE,
                    PrivacyPhase.SESSION,
                    "Privacy context is unknown or already closed"
            );
        }
    }

    private Generation protectGeneration(
            PrivacyContextHandle handle,
            Generation generation
    ) {
        boolean returnDirect = isReturnDirect(generation.getMetadata());
        AssistantMessage originalMessage = generation.getOutput();
        AssistantMessage protectedMessage = originalMessage == null
                ? null
                : protectMessage(handle, originalMessage, returnDirect);
        ChatGenerationMetadata originalMetadata = generation.getMetadata();
        ChatGenerationMetadata protectedMetadata = PrivacyProviderTextMetadataTransformer.transformGenerationMetadata(
                originalMetadata,
                text -> protectText(handle, text, returnDirect)
        );
        if (protectedMessage == originalMessage && protectedMetadata == originalMetadata) {
            return generation;
        }
        return new Generation(protectedMessage, protectedMetadata);
    }

    private AssistantMessage protectMessage(
            PrivacyContextHandle handle,
            AssistantMessage message,
            boolean returnDirect
    ) {
        this.modelControlValidator.validateSensitiveControlFields(handle, message);
        return PrivacyMessageTransformer.transformAssistantMessage(
                message,
                text -> protectText(handle, text, returnDirect)
        );
    }

    private String protectText(
            PrivacyContextHandle handle,
            String text,
            boolean returnDirect
    ) {
        String policyInput = returnDirect
                ? PrivacyJsonPayloadTransformer.restoreKnownTokens(
                        this.privacyService,
                        handle,
                        text,
                        PrivacyPhase.OUTPUT_POLICY
                )
                : text;
        PrivacyOutputPolicyExecutor.Result policyResult = PrivacyOutputPolicyExecutor.apply(
                this.privacyService,
                handle,
                policyInput,
                this.action
        );
        if (policyResult.blocked()) {
            notifyApplicationOutput(PrivacyEnforcementOutcome.BLOCKED);
            throw new PrivacyOutputBlockedException(this.blockExceptionMessage);
        }
        return policyResult.text();
    }

    private ChatClientRequest prepareOutputBoundaryRequest(
            ChatClientRequest request,
            PrivacyContextHandle handle
    ) {
        ChatClientRequest requestWithInspectionLimits =
                PrivacyOutputContextSupport.attachResponseInspectionLimits(
                        request,
                        this.responseInspectionLimits
                );
        return PrivacyRequestContextSupport.attachHandle(requestWithInspectionLimits, handle);
    }

    private Flux<ChatClientResponse> bufferAndProtect(
            PrivacyContextHandle handle,
            Flux<ChatClientResponse> responses
    ) {
        Objects.requireNonNull(responses, "responses must not be null");
        return Flux.defer(() -> {
            PrivacyResponseInspectionGuard inspectionGuard =
                    new PrivacyResponseInspectionGuard(this.responseInspectionLimits);
            return responses
                    .timeout(
                            this.responseInspectionLimits.streamIdleTimeout(),
                            Flux.error(streamTimeout())
                    )
                    .map(response -> {
                        inspectionGuard.accept(response);
                        return response;
                    })
                    .collectList()
                    .map(buffered -> PrivacyBufferedStreamTransformer.transform(
                            buffered,
                            (message, metadata) -> protectMessage(
                                    handle,
                                    message,
                                    isReturnDirect(metadata)
                            ),
                            metadata -> PrivacyProviderTextMetadataTransformer.transformGenerationMetadata(
                                    metadata,
                                    text -> protectText(handle, text, isReturnDirect(metadata))
                            )
                    ))
                    .doOnNext(ignored -> notifyApplicationOutput(
                            PrivacyEnforcementOutcome.PROTECTED
                    ))
                    .flatMapMany(Flux::fromIterable);
        });
    }

    private static boolean isReturnDirect(ChatGenerationMetadata metadata) {
        return metadata != null
                && ToolExecutionResult.FINISH_REASON.equals(metadata.getFinishReason());
    }

    private PrivacyGuardrailException streamTimeout() {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.STREAM_TIMEOUT,
                PrivacyPhase.OUTPUT_POLICY,
                "Streaming response exceeded the configured privacy idle timeout"
        );
    }

    private void notifyApplicationOutput(PrivacyEnforcementOutcome outcome) {
        this.enforcementNotifier.notify(
                PrivacyEnforcementBoundary.APPLICATION_OUTPUT,
                outcome
        );
    }
}
