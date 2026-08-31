package io.github.ultramancode.springai.privacy.security;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Enforces definition filtering and execution-time reauthorization. */
final class AuthorizationAwareToolCallingManager implements ToolCallingManager {

    private final AuthorizationManager<ToolAuthorizationContext> authorizationManager;
    private final SecurityToolSessionRegistry registry;
    private final ToolCallingManager delegate;

    AuthorizationAwareToolCallingManager(
            AuthorizationManager<ToolAuthorizationContext> authorizationManager,
            SecurityToolSessionRegistry registry,
            ToolCallingManager delegate
    ) {
        this.authorizationManager = Objects.requireNonNull(
                authorizationManager,
                "authorizationManager must not be null"
        );
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        Objects.requireNonNull(chatOptions, "chatOptions must not be null");
        List<ToolCallback> callbacks = callbacks(chatOptions);
        if (callbacks.isEmpty()) {
            return List.of();
        }
        SecurityToolSessionRegistry.State state = requireActiveSessionState(chatOptions);
        List<ToolDefinition> authorizedDefinitions = new ArrayList<>();
        for (ToolCallback callback : callbacks) {
            ToolCallback current = state.requireCurrentCallback(callback, chatOptions);
            ToolDefinition definition = current.getToolDefinition();
            if (state.isToolSearchControl(current)) {
                state.markExposed(definition.name());
                authorizedDefinitions.add(definition);
                continue;
            }
            if (isGranted(state, definition, ToolAuthorizationPhase.DEFINITION)) {
                state.markExposed(definition.name());
                authorizedDefinitions.add(definition);
            }
        }
        return List.copyOf(authorizedDefinitions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Objects.requireNonNull(prompt, "prompt must not be null");
        Objects.requireNonNull(chatResponse, "chatResponse must not be null");
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            throw SecurityToolSessionRegistry.denied(
                    "Tool execution requires ToolCallingChatOptions"
            );
        }
        SecurityToolSessionRegistry.State state = requireActiveSessionState(options);
        Map<String, ToolCallback> currentCallbacks = indexCurrentCallbacks(
                state,
                options,
                callbacks(options)
        );
        List<AssistantMessage.ToolCall> toolCalls = requestedToolCalls(chatResponse);
        Set<String> requestedNames = new LinkedHashSet<>();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.name();
            ToolCallback callback = currentCallbacks.get(toolName);
            if (callback == null || !state.wasExposed(toolName)) {
                throw SecurityToolSessionRegistry.denied(
                        "A model-requested tool was not exposed by the authorization boundary"
                );
            }
            if (requestedNames.add(toolName) && !state.isToolSearchControl(callback)) {
                requireExecutionAuthorization(
                        this.authorizationManager,
                        state,
                        callback.getToolDefinition()
                );
            }
        }

        List<ToolCallback> securedCallbacks = requestedNames.stream()
                .map(currentCallbacks::get)
                .map(callback -> state.isToolSearchControl(callback)
                        ? callback
                        : new ReauthorizingToolCallback(
                                callback,
                                state,
                                this.authorizationManager
                        ))
                .map(ToolCallback.class::cast)
                .toList();
        // mutate() retains the original map and toolContext(Map) merges entries, so clear it
        // before applying the context without the internal security handle.
        ToolCallingChatOptions securedOptions = options.mutate()
                .toolCallbacks(securedCallbacks)
                .toolContext(null)
                .toolContext(toolContextWithoutSecurityHandle(options))
                .build();
        Prompt securedPrompt = new Prompt(prompt.getInstructions(), securedOptions);
        return this.delegate.executeToolCalls(securedPrompt, chatResponse);
    }

    private SecurityToolSessionRegistry.State requireActiveSessionState(
            ToolCallingChatOptions options
    ) {
        Map<String, Object> context = options.getToolContext();
        Object value = context == null
                ? null
                : context.get(SecurityToolSessionRegistry.TOOL_CONTEXT_HANDLE);
        if (!(value instanceof SecurityToolContextHandle handle)) {
            throw SecurityToolSessionRegistry.denied(
                    "Tool authorization context is missing or invalid"
            );
        }
        return this.registry.requireActiveSessionState(handle);
    }

    private Map<String, ToolCallback> indexCurrentCallbacks(
            SecurityToolSessionRegistry.State state,
            ToolCallingChatOptions options,
            List<ToolCallback> callbacks
    ) {
        Map<String, ToolCallback> indexed = new LinkedHashMap<>();
        for (ToolCallback callback : callbacks) {
            ToolCallback current = state.requireCurrentCallback(callback, options);
            String name = current.getToolDefinition().name();
            if (indexed.putIfAbsent(name, current) != null) {
                throw new IllegalArgumentException("tool callback names must be unique");
            }
        }
        return Map.copyOf(indexed);
    }

    private boolean isGranted(
            SecurityToolSessionRegistry.State state,
            ToolDefinition definition,
            ToolAuthorizationPhase phase
    ) {
        AuthorizationResult result = this.authorizationManager.authorize(
                state::authentication,
                new ToolAuthorizationContext(definition, phase)
        );
        return result != null && result.isGranted();
    }

    private static void requireExecutionAuthorization(
            AuthorizationManager<ToolAuthorizationContext> authorizationManager,
            SecurityToolSessionRegistry.State state,
            ToolDefinition definition
    ) {
        AuthorizationResult result = authorizationManager.authorize(
                state::authentication,
                new ToolAuthorizationContext(definition, ToolAuthorizationPhase.EXECUTION)
        );
        if (result == null || !result.isGranted()) {
            throw denied(result);
        }
    }

    private static AuthorizationDeniedException denied(AuthorizationResult result) {
        return result == null
                ? new AuthorizationDeniedException("Tool execution was not authorized")
                : new AuthorizationDeniedException("Tool execution was not authorized", result);
    }

    private static List<ToolCallback> callbacks(ToolCallingChatOptions options) {
        return options.getToolCallbacks() == null
                ? List.of()
                : List.copyOf(options.getToolCallbacks());
    }

    private static List<AssistantMessage.ToolCall> requestedToolCalls(ChatResponse response) {
        return response.getResults().stream()
                .map(Generation::getOutput)
                .map(AssistantMessage::getToolCalls)
                .filter(toolCalls -> toolCalls != null && !toolCalls.isEmpty())
                .findFirst()
                .map(List::copyOf)
                .orElseThrow(() -> new IllegalStateException(
                        "No tool call requested by the chat model"
                ));
    }

    private static Map<String, Object> toolContextWithoutSecurityHandle(
            ToolCallingChatOptions options
    ) {
        Map<String, Object> context = new HashMap<>();
        if (options.getToolContext() != null) {
            context.putAll(options.getToolContext());
        }
        context.remove(SecurityToolSessionRegistry.TOOL_CONTEXT_HANDLE);
        return Map.copyOf(context);
    }

    private static final class ReauthorizingToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final SecurityToolSessionRegistry.State state;
        private final AuthorizationManager<ToolAuthorizationContext> authorizationManager;

        private ReauthorizingToolCallback(
                ToolCallback delegate,
                SecurityToolSessionRegistry.State state,
                AuthorizationManager<ToolAuthorizationContext> authorizationManager
        ) {
            this.delegate = delegate;
            this.state = state;
            this.authorizationManager = authorizationManager;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return this.delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return this.delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            reauthorize();
            return this.delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            reauthorize();
            return this.delegate.call(toolInput, toolContext);
        }

        private void reauthorize() {
            requireExecutionAuthorization(
                    this.authorizationManager,
                    this.state,
                    getToolDefinition()
            );
        }
    }
}
