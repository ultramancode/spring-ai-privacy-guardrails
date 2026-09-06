package io.github.ultramancode.springai.privacy.security;

import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Stores request {@link Authentication}, initial callback identities, and exposed tool-definition
 * names in an internal registry. Only an opaque handle is attached to Spring AI tool options.
 */
final class ToolAuthorizationSessionRegistry {

    static final String TOOL_AUTHORIZATION_SESSION_HANDLE_KEY =
            "io.github.ultramancode.springai.privacy.security.tool-authorization-session-handle";

    private final ConcurrentMap<ToolAuthorizationSessionHandle, SessionState> sessions =
            new ConcurrentHashMap<>();

    ToolAuthorizationSession openSession(
            Authentication authentication,
            List<ToolCallback> initialToolCallbacks
    ) {
        Objects.requireNonNull(authentication, "authentication must not be null");
        SessionState sessionState = new SessionState(authentication, initialToolCallbacks);
        ToolAuthorizationSessionHandle handle;
        do {
            handle = ToolAuthorizationSessionHandle.create();
        }
        while (this.sessions.putIfAbsent(handle, sessionState) != null);
        return new ToolAuthorizationSession(this, handle);
    }

    SessionState requireActiveSessionState(ToolAuthorizationSessionHandle handle) {
        SessionState sessionState = this.sessions.get(
                Objects.requireNonNull(handle, "handle must not be null")
        );
        if (sessionState == null) {
            throw denied("Tool authorization session is missing or expired");
        }
        return sessionState;
    }

    void close(ToolAuthorizationSessionHandle handle) {
        this.sessions.remove(handle);
    }

    int activeSessionCount() {
        return this.sessions.size();
    }

    static AuthorizationDeniedException denied(String message) {
        return new AuthorizationDeniedException(message);
    }

    static final class SessionState {

        private final Authentication authentication;
        private final Map<String, ToolCallback> initialCallbacksByName;
        private final Set<String> exposedToolDefinitionNames = ConcurrentHashMap.newKeySet();
        private volatile ToolCallback toolSearchToolCallback;

        private SessionState(
                Authentication authentication,
                List<ToolCallback> initialToolCallbacks
        ) {
            this.authentication = authentication;
            Objects.requireNonNull(
                    initialToolCallbacks,
                    "initialToolCallbacks must not be null"
            );
            Map<String, ToolCallback> callbacksByName = new LinkedHashMap<>();
            for (ToolCallback callbackValue : initialToolCallbacks) {
                ToolCallback callback = Objects.requireNonNull(
                        callbackValue,
                        "tool callbacks must not contain null values"
                );
                String name = callback.getToolDefinition().name();
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("tool callback names must not be blank");
                }
                if (callbacksByName.putIfAbsent(name, callback) != null) {
                    throw new IllegalArgumentException(
                            "tool callback names must be unique within an authorization session"
                    );
                }
            }
            this.initialCallbacksByName = Map.copyOf(callbacksByName);
        }

        Authentication authentication() {
            return this.authentication;
        }

        ToolCallback requireStableCallback(
                ToolCallback callback,
                ToolCallingChatOptions options
        ) {
            Objects.requireNonNull(callback, "callback must not be null");
            String name = callback.getToolDefinition().name();
            ToolCallback initialCallback = this.initialCallbacksByName.get(name);
            if (initialCallback == null) {
                if (!SpringAiToolSearchSupport.isToolSearchToolCallback(callback, options)) {
                    throw denied("A tool callback was added after authorization");
                }
                return pinToolSearchToolCallback(callback);
            }
            if (initialCallback != callback) {
                throw denied("A tool callback was replaced after authorization");
            }
            return initialCallback;
        }

        // Spring AI adds its Tool Search tool callback after the initial callback snapshot.
        // Pin the first instance so this control-plane callback cannot be replaced.
        private synchronized ToolCallback pinToolSearchToolCallback(ToolCallback callback) {
            ToolCallback pinned = this.toolSearchToolCallback;
            if (pinned == null) {
                this.toolSearchToolCallback = callback;
            } else if (pinned != callback) {
                throw denied("The Tool Search tool callback changed during the request");
            }
            return callback;
        }

        boolean isToolSearchToolCallback(ToolCallback callback) {
            return this.toolSearchToolCallback == callback;
        }

        void markDefinitionExposed(String toolName) {
            this.exposedToolDefinitionNames.add(toolName);
        }

        boolean wasDefinitionExposed(String toolName) {
            return this.exposedToolDefinitionNames.contains(toolName);
        }
    }
}
