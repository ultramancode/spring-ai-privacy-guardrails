package io.github.ultramancode.springai.privacy.security;

import io.github.ultramancode.springai.privacy.springai.SpringAiToolSearchSupport;
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

/** Stores Authentication outside request and tool context behind an opaque handle. */
final class SecurityToolSessionRegistry {

    static final String TOOL_CONTEXT_HANDLE =
            "io.github.ultramancode.springai.privacy.security.context-handle";

    private final ConcurrentMap<SecurityToolContextHandle, State> sessions =
            new ConcurrentHashMap<>();

    SecurityToolSession open(Authentication authentication, List<ToolCallback> callbacks) {
        Objects.requireNonNull(authentication, "authentication must not be null");
        State state = new State(authentication, callbacks);
        SecurityToolContextHandle handle;
        do {
            handle = SecurityToolContextHandle.create();
        }
        while (this.sessions.putIfAbsent(handle, state) != null);
        return new SecurityToolSession(this, handle);
    }

    State require(SecurityToolContextHandle handle) {
        State state = this.sessions.get(Objects.requireNonNull(handle, "handle must not be null"));
        if (state == null) {
            throw denied("Tool authorization context is missing or expired");
        }
        return state;
    }

    void close(SecurityToolContextHandle handle) {
        this.sessions.remove(handle);
    }

    int activeSessionCount() {
        return this.sessions.size();
    }

    static AuthorizationDeniedException denied(String message) {
        return new AuthorizationDeniedException(message);
    }

    static final class State {

        private final Authentication authentication;
        private final Map<String, ToolCallback> declaredCallbacks;
        private final Set<String> exposedToolNames = ConcurrentHashMap.newKeySet();
        private volatile ToolCallback toolSearchControlCallback;

        private State(Authentication authentication, List<ToolCallback> callbacks) {
            this.authentication = authentication;
            Objects.requireNonNull(callbacks, "callbacks must not be null");
            Map<String, ToolCallback> declared = new LinkedHashMap<>();
            for (ToolCallback callbackValue : callbacks) {
                ToolCallback callback = Objects.requireNonNull(
                        callbackValue,
                        "tool callbacks must not contain null values"
                );
                String name = callback.getToolDefinition().name();
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("tool callback names must not be blank");
                }
                if (declared.putIfAbsent(name, callback) != null) {
                    throw new IllegalArgumentException(
                            "tool callback names must be unique within an authorization session"
                    );
                }
            }
            this.declaredCallbacks = Map.copyOf(declared);
        }

        Authentication authentication() {
            return this.authentication;
        }

        ToolCallback requireCurrent(
                ToolCallback callback,
                ToolCallingChatOptions options
        ) {
            Objects.requireNonNull(callback, "callback must not be null");
            String name = callback.getToolDefinition().name();
            ToolCallback declared = this.declaredCallbacks.get(name);
            if (declared != null) {
                if (declared != callback) {
                    throw denied("A tool callback was replaced after authorization");
                }
                return declared;
            }
            if (!SpringAiToolSearchSupport.isControlCallback(callback, options)) {
                throw denied("A tool callback was added after authorization");
            }
            return admitToolSearchControl(callback);
        }

        private synchronized ToolCallback admitToolSearchControl(ToolCallback callback) {
            if (this.toolSearchControlCallback == null) {
                this.toolSearchControlCallback = callback;
                return callback;
            }
            if (this.toolSearchControlCallback != callback) {
                throw denied("The Tool Search control callback was replaced after admission");
            }
            return callback;
        }

        boolean isToolSearchControl(ToolCallback callback) {
            return this.toolSearchControlCallback == callback;
        }

        void markExposed(String toolName) {
            this.exposedToolNames.add(toolName);
        }

        boolean wasExposed(String toolName) {
            return this.exposedToolNames.contains(toolName);
        }
    }
}
