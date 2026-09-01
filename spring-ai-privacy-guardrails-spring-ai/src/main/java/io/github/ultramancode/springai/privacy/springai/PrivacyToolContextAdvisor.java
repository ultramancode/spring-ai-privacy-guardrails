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
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Copies the active opaque privacy handle into the tool options observed at this advisor's
 * configured position. Register this advisor when tools can be supplied or injected
 * dynamically. Every application callback observed here must be created by
 * {@link PrivacyToolCallbackFactory}. Spring AI's built-in Tool Search tool callback acts
 * as control-plane infrastructure and is the only unwrapped callback accepted. It cannot
 * disclose original values, and the same callback instance must be retained for the request.
 * The default order matches the tested standard layout. Later callback changes are checked
 * again before model invocation. Applications that change advisor order must ensure that
 * custom advisors do not mutate tool options after the final privacy check.
 */
public final class PrivacyToolContextAdvisor implements CallAdvisor, StreamAdvisor {

    /** Tested position immediately before Spring AI captures tool execution options. */
    public static final int DEFAULT_ORDER = ToolCallingAdvisor.DEFAULT_ORDER - 1;
    private static final String UNWRAPPED_TOOL_MESSAGE =
            "PrivacyToolContextAdvisor rejected a tool callback outside the privacy boundary";
    private static final String WRONG_SERVICE_MESSAGE =
            "PrivacyToolContextAdvisor rejected a tool callback bound to another privacy service";
    private static final String WRONG_FACTORY_MESSAGE =
            "PrivacyToolContextAdvisor rejected a tool callback from another privacy factory";
    private static final String DUPLICATE_TOOL_MESSAGE =
            "PrivacyToolContextAdvisor rejected duplicate tool callback names";

    private final PrivacyService privacyService;
    private final PrivacyToolCallbackFactory.Provenance requiredFactoryProvenance;
    private final int order;

    /**
     * Creates a service-bound tool-context boundary at {@link #DEFAULT_ORDER}.
     *
     * @param privacyService service that owns request sessions and transformations
     */
    public PrivacyToolContextAdvisor(PrivacyService privacyService) {
        this(privacyService, null, DEFAULT_ORDER);
    }

    /**
     * Creates a tool-context boundary at an application-selected advisor order.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param order Spring AI advisor order
     */
    public PrivacyToolContextAdvisor(PrivacyService privacyService, int order) {
        this(privacyService, null, order);
    }

    /**
     * Creates a boundary that additionally accepts only wrappers created by the
     * supplied factory. Boot uses this constructor. Direct integrations may keep the
     * service-only constructor when multiple factories are intentional.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param requiredFactory factory whose wrappers are accepted, or {@code null} to
     * accept wrappers from any factory using the same service
     */
    public PrivacyToolContextAdvisor(
            PrivacyService privacyService,
            PrivacyToolCallbackFactory requiredFactory
    ) {
        this(privacyService, requiredFactory, DEFAULT_ORDER);
    }

    /**
     * Creates a factory-bound tool-context boundary at an application-selected order.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param requiredFactory factory whose wrappers are accepted, or {@code null} to
     * accept wrappers from any factory using the same service
     * @param order Spring AI advisor order
     */
    public PrivacyToolContextAdvisor(
            PrivacyService privacyService,
            PrivacyToolCallbackFactory requiredFactory,
            int order
    ) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
        if (requiredFactory != null && !requiredFactory.usesPrivacyService(privacyService)) {
            throw new IllegalArgumentException("requiredFactory must use the same PrivacyService");
        }
        this.requiredFactoryProvenance = requiredFactory == null ? null : requiredFactory.provenance();
        this.order = order;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(attachToCurrentOptions(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return chain.nextStream(attachToCurrentOptions(request));
    }

    @Override
    public String getName() {
        return "PrivacyToolContextAdvisor";
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    private ChatClientRequest attachToCurrentOptions(ChatClientRequest request) {
        PrivacyContextHandle handle = PrivacyRequestContextSupport.findHandle(request)
                .orElseThrow(() -> new PrivacyGuardrailException(
                        PrivacyFailureCode.CONTEXT_REQUIRED,
                        PrivacyPhase.SESSION,
                        "PrivacyToolContextAdvisor requires an active request privacy session"
                ));
        if (!this.privacyService.isSessionActive(handle)) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.CONTEXT_NOT_ACTIVE,
                    PrivacyPhase.SESSION,
                    "Privacy context is unknown or already closed"
            );
        }
        requirePrivacyWrappedToolNames(request, this.privacyService, this.requiredFactoryProvenance);
        ChatClientRequest attached = PrivacyRequestContextSupport.attachHandle(request, handle);
        return PrivacyToolExecutionContextSupport.attachValidatedToolCallbackSnapshot(attached);
    }

    static Set<String> requirePrivacyWrappedToolNames(
            ChatClientRequest request,
            PrivacyService expectedService,
            PrivacyToolCallbackFactory.Provenance requiredFactoryProvenance
    ) {
        Objects.requireNonNull(request, "request must not be null");
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions toolCallingOptions)) {
            return Set.of();
        }
        List<ToolCallback> callbacks = toolCallingOptions.getToolCallbacks();
        if (callbacks == null || callbacks.isEmpty()) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>(callbacks.size());
        for (ToolCallback callback : callbacks) {
            if (SpringAiToolSearchSupport.isToolSearchToolCallback(
                    callback,
                    toolCallingOptions
            )) {
                if (!names.add(callback.getToolDefinition().name())) {
                    throw new PrivacyGuardrailException(
                            PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                            PrivacyPhase.TOOL_INPUT,
                            DUPLICATE_TOOL_MESSAGE
                    );
                }
                continue;
            }
            if (!(callback instanceof PrivacyToolCallbackWrapper wrapper)) {
                throw new PrivacyGuardrailException(
                        PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                        PrivacyPhase.TOOL_INPUT,
                        UNWRAPPED_TOOL_MESSAGE
                );
            }
            if (expectedService != null && !wrapper.usesPrivacyService(expectedService)) {
                throw new PrivacyGuardrailException(
                        PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                        PrivacyPhase.TOOL_INPUT,
                        WRONG_SERVICE_MESSAGE
                );
            }
            if (requiredFactoryProvenance != null
                    && !wrapper.hasFactoryProvenance(requiredFactoryProvenance)) {
                throw new PrivacyGuardrailException(
                        PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                        PrivacyPhase.TOOL_INPUT,
                        WRONG_FACTORY_MESSAGE
                );
            }
            if (!names.add(wrapper.getToolDefinition().name())) {
                throw new PrivacyGuardrailException(
                        PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                        PrivacyPhase.TOOL_INPUT,
                        DUPLICATE_TOOL_MESSAGE
                );
            }
        }
        return Set.copyOf(names);
    }
}
