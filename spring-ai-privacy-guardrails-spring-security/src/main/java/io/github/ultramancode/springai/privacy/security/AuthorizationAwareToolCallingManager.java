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
    private final ToolAuthorizationSessionRegistry sessionRegistry;
    private final ToolCallingManager delegate;

    AuthorizationAwareToolCallingManager(
            AuthorizationManager<ToolAuthorizationContext> authorizationManager,
            ToolAuthorizationSessionRegistry sessionRegistry,
            ToolCallingManager delegate
    ) {
        this.authorizationManager = Objects.requireNonNull(
                authorizationManager,
                "authorizationManager must not be null"
        );
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry must not be null"
        );
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        Objects.requireNonNull(chatOptions, "chatOptions must not be null");
        List<ToolCallback> toolCallbacks = toolCallbacks(chatOptions);
        if (toolCallbacks.isEmpty()) {
            return List.of();
        }
        ToolAuthorizationSessionRegistry.SessionState sessionState =
                requireActiveSessionState(chatOptions);
        List<ToolDefinition> authorizedDefinitions = new ArrayList<>();
        for (ToolCallback callback : toolCallbacks) {
            ToolCallback stableCallback = sessionState.requireStableCallback(
                    callback,
                    chatOptions
            );
            ToolDefinition definition = stableCallback.getToolDefinition();
            if (sessionState.isToolSearchToolCallback(stableCallback)) {
                sessionState.markDefinitionExposed(definition.name());
                authorizedDefinitions.add(definition);
                continue;
            }
            if (isGranted(sessionState, definition, ToolAuthorizationPhase.DEFINITION)) {
                sessionState.markDefinitionExposed(definition.name());
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
            throw ToolAuthorizationSessionRegistry.denied(
                    "Tool execution requires ToolCallingChatOptions"
            );
        }
        ToolAuthorizationSessionRegistry.SessionState sessionState =
                requireActiveSessionState(options);
        Map<String, ToolCallback> stableCallbacksByName = buildStableCallbacksByName(
                sessionState,
                options,
                toolCallbacks(options)
        );
        List<AssistantMessage.ToolCall> requestedToolCalls = firstToolCallBatch(chatResponse);
        Set<String> requestedToolNames = new LinkedHashSet<>();
        // Preauthorize the entire model-requested batch before the delegate can execute callbacks.
        // If any tool is denied at this stage, block the whole batch to avoid partial side effects.
        for (AssistantMessage.ToolCall toolCall : requestedToolCalls) {
            String toolName = toolCall.name();
            ToolCallback callback = stableCallbacksByName.get(toolName);
            if (callback == null || !sessionState.wasDefinitionExposed(toolName)) {
                throw ToolAuthorizationSessionRegistry.denied(
                        "A model-requested tool was not exposed by the authorization boundary"
                );
            }
            if (!requestedToolNames.add(toolName)) {
                continue;
            }
            if (sessionState.isToolSearchToolCallback(callback)) {
                continue;
            }
            requireExecutionAuthorization(
                    this.authorizationManager,
                    sessionState,
                    callback.getToolDefinition()
            );
        }

        // Preserve Spring AI's pinned Tool Search tool callback. Business callbacks are
        // wrapped so authorization is checked again immediately before their execution.
        List<ToolCallback> executionCallbacks = requestedToolNames.stream()
                .map(stableCallbacksByName::get)
                .map(callback -> {
                    if (sessionState.isToolSearchToolCallback(callback)) {
                        return callback;
                    }
                    return new ReauthorizingToolCallback(
                            callback,
                            sessionState,
                            this.authorizationManager
                    );
                })
                .toList();
        // mutate() copies the original tool-context entries, while toolContext(Map) merges.
        // Clear them first, then apply a copy without the authorization-session handle.
        ToolCallingChatOptions executionOptions = options.mutate()
                .toolCallbacks(executionCallbacks)
                .toolContext(null)
                .toolContext(toolContextWithoutAuthorizationSessionHandle(options))
                .build();
        Prompt executionPrompt = new Prompt(prompt.getInstructions(), executionOptions);
        return this.delegate.executeToolCalls(executionPrompt, chatResponse);
    }

    private ToolAuthorizationSessionRegistry.SessionState requireActiveSessionState(
            ToolCallingChatOptions options
    ) {
        Map<String, Object> toolContext = options.getToolContext();
        Object sessionHandleValue = toolContext == null
                ? null
                : toolContext.get(
                        ToolAuthorizationSessionRegistry.TOOL_AUTHORIZATION_SESSION_HANDLE_KEY
                );
        if (!(sessionHandleValue instanceof ToolAuthorizationSessionHandle handle)) {
            throw ToolAuthorizationSessionRegistry.denied(
                    "Tool authorization session handle is missing or invalid"
            );
        }
        return this.sessionRegistry.requireActiveSessionState(handle);
    }

    private Map<String, ToolCallback> buildStableCallbacksByName(
            ToolAuthorizationSessionRegistry.SessionState sessionState,
            ToolCallingChatOptions options,
            List<ToolCallback> toolCallbacks
    ) {
        Map<String, ToolCallback> callbacksByName = new LinkedHashMap<>();
        for (ToolCallback callback : toolCallbacks) {
            ToolCallback stableCallback = sessionState.requireStableCallback(callback, options);
            String name = stableCallback.getToolDefinition().name();
            if (callbacksByName.putIfAbsent(name, stableCallback) != null) {
                throw new IllegalArgumentException("tool callback names must be unique");
            }
        }
        return Map.copyOf(callbacksByName);
    }

    private boolean isGranted(
            ToolAuthorizationSessionRegistry.SessionState sessionState,
            ToolDefinition definition,
            ToolAuthorizationPhase phase
    ) {
        AuthorizationResult result = this.authorizationManager.authorize(
                sessionState::authentication,
                new ToolAuthorizationContext(definition, phase)
        );
        return result != null && result.isGranted();
    }

    private static void requireExecutionAuthorization(
            AuthorizationManager<ToolAuthorizationContext> authorizationManager,
            ToolAuthorizationSessionRegistry.SessionState sessionState,
            ToolDefinition definition
    ) {
        AuthorizationResult result = authorizationManager.authorize(
                sessionState::authentication,
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

    private static List<ToolCallback> toolCallbacks(ToolCallingChatOptions options) {
        return options.getToolCallbacks() == null
                ? List.of()
                : List.copyOf(options.getToolCallbacks());
    }

    private static List<AssistantMessage.ToolCall> firstToolCallBatch(ChatResponse response) {
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

    private static Map<String, Object> toolContextWithoutAuthorizationSessionHandle(
            ToolCallingChatOptions options
    ) {
        Map<String, Object> context = new HashMap<>();
        if (options.getToolContext() != null) {
            context.putAll(options.getToolContext());
        }
        context.remove(
                ToolAuthorizationSessionRegistry.TOOL_AUTHORIZATION_SESSION_HANDLE_KEY
        );
        return Map.copyOf(context);
    }

    private static final class ReauthorizingToolCallback implements ToolCallback {

        private final ToolCallback delegate;
        private final ToolAuthorizationSessionRegistry.SessionState sessionState;
        private final AuthorizationManager<ToolAuthorizationContext> authorizationManager;

        private ReauthorizingToolCallback(
                ToolCallback delegate,
                ToolAuthorizationSessionRegistry.SessionState sessionState,
                AuthorizationManager<ToolAuthorizationContext> authorizationManager
        ) {
            this.delegate = delegate;
            this.sessionState = sessionState;
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
                    this.sessionState,
                    getToolDefinition()
            );
        }
    }
}
