package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stores and validates request-scoped tool execution metadata. */
final class PrivacyToolExecutionContextSupport {

    private static final String CONTEXT_REGISTERED_TOOL_NAMES =
            "io.github.ultramancode.springai.privacy.registered-tool-names";
    private static final String CONTEXT_VALIDATED_TOOL_CALLBACK_SNAPSHOT =
            "io.github.ultramancode.springai.privacy.validated-tool-callback-snapshot";
    private PrivacyToolExecutionContextSupport() {
    }

    static ChatClientRequest attachRegisteredToolNames(
            ChatClientRequest request,
            Set<String> registeredToolNames
    ) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(registeredToolNames, "registeredToolNames must not be null");
        Map<String, Object> context = new HashMap<>(request.context());
        context.put(
                CONTEXT_REGISTERED_TOOL_NAMES,
                new RegisteredToolNames(Set.copyOf(registeredToolNames))
        );
        return request.mutate().context(context).build();
    }

    static ChatClientRequest attachValidatedToolCallbackSnapshot(ChatClientRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        boolean toolCallingOptionsPresent = request.prompt().getOptions() instanceof ToolCallingChatOptions;
        List<ToolCallback> callbacks = toolCallingOptionsPresent
                && ((ToolCallingChatOptions) request.prompt().getOptions()).getToolCallbacks() != null
                ? ((ToolCallingChatOptions) request.prompt().getOptions()).getToolCallbacks()
                : List.of();
        Map<String, Object> context = new HashMap<>(request.context());
        context.put(
                CONTEXT_VALIDATED_TOOL_CALLBACK_SNAPSHOT,
                new ValidatedToolCallbackSnapshot(toolCallingOptionsPresent, List.copyOf(callbacks))
        );
        return request.mutate().context(context).build();
    }

    static void requireCallbacksMatchValidatedSnapshot(ChatClientRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Object snapshotValue = request.context().get(CONTEXT_VALIDATED_TOOL_CALLBACK_SNAPSHOT);
        if (!(snapshotValue instanceof ValidatedToolCallbackSnapshot snapshot)) {
            throw callbackSnapshotFailure();
        }
        boolean toolCallingOptionsPresent = request.prompt().getOptions() instanceof ToolCallingChatOptions;
        if (toolCallingOptionsPresent != snapshot.toolCallingOptionsPresent()) {
            throw callbackSnapshotFailure();
        }
        List<ToolCallback> currentCallbacks = toolCallingOptionsPresent
                && ((ToolCallingChatOptions) request.prompt().getOptions()).getToolCallbacks() != null
                ? ((ToolCallingChatOptions) request.prompt().getOptions()).getToolCallbacks()
                : List.of();
        ToolCallingChatOptions options = toolCallingOptionsPresent
                ? (ToolCallingChatOptions) request.prompt().getOptions()
                : null;
        snapshot.requireCompatible(currentCallbacks, options);
    }

    static Set<String> requireRegisteredToolNames(ChatClientResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        Object registeredToolNamesValue = response.context().get(CONTEXT_REGISTERED_TOOL_NAMES);
        if (registeredToolNamesValue instanceof RegisteredToolNames registeredToolNames) {
            return registeredToolNames.names();
        }
        throw new PrivacyGuardrailException(
                PrivacyFailureCode.CONTEXT_REQUIRED,
                PrivacyPhase.TOOL_INPUT,
                "Tool execution response is missing validated privacy metadata"
        );
    }

    static boolean hasInternalEntries(Map<String, Object> context) {
        return context.containsKey(CONTEXT_REGISTERED_TOOL_NAMES)
                || context.containsKey(CONTEXT_VALIDATED_TOOL_CALLBACK_SNAPSHOT);
    }

    static void removeInternalEntries(Map<String, Object> context) {
        context.remove(CONTEXT_REGISTERED_TOOL_NAMES);
        context.remove(CONTEXT_VALIDATED_TOOL_CALLBACK_SNAPSHOT);
    }

    private static PrivacyGuardrailException callbackSnapshotFailure() {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                PrivacyPhase.TOOL_INPUT,
                "Tool callbacks changed after the privacy tool-context boundary"
        );
    }

    private record RegisteredToolNames(Set<String> names) {
    }

    private static final class ValidatedToolCallbackSnapshot {

        private final boolean toolCallingOptionsPresent;
        private final List<ToolCallback> callbacks;
        private ToolCallback toolSearchControlCallback;

        private ValidatedToolCallbackSnapshot(
                boolean toolCallingOptionsPresent,
                List<ToolCallback> callbacks
        ) {
            this.toolCallingOptionsPresent = toolCallingOptionsPresent;
            this.callbacks = callbacks;
        }

        private boolean toolCallingOptionsPresent() {
            return this.toolCallingOptionsPresent;
        }

        private synchronized void requireCompatible(
                List<ToolCallback> currentCallbacks,
                ToolCallingChatOptions options
        ) {
            if (matchesOriginalSnapshot(currentCallbacks)) {
                return;
            }
            Set<String> currentNames = new HashSet<>();
            boolean foundToolSearchControl = false;
            for (ToolCallback callback : currentCallbacks) {
                String name = callback.getToolDefinition().name();
                if (!currentNames.add(name)) {
                    throw callbackSnapshotFailure();
                }
                if (containsOriginalCallback(callback)) {
                    continue;
                }
                if (!SpringAiToolSearchSupport.isControlCallback(callback, options)
                        || foundToolSearchControl) {
                    throw callbackSnapshotFailure();
                }
                admitToolSearchControl(callback);
                foundToolSearchControl = true;
            }
            if (!foundToolSearchControl) {
                throw callbackSnapshotFailure();
            }
        }

        private boolean matchesOriginalSnapshot(List<ToolCallback> currentCallbacks) {
            if (currentCallbacks.size() != this.callbacks.size()) {
                return false;
            }
            for (int index = 0; index < currentCallbacks.size(); index++) {
                if (currentCallbacks.get(index) != this.callbacks.get(index)) {
                    return false;
                }
            }
            return true;
        }

        private boolean containsOriginalCallback(ToolCallback candidate) {
            return this.callbacks.stream().anyMatch(callback -> callback == candidate);
        }

        private void admitToolSearchControl(ToolCallback callback) {
            if (this.toolSearchControlCallback == null) {
                this.toolSearchControlCallback = callback;
                return;
            }
            if (this.toolSearchControlCallback != callback) {
                throw callbackSnapshotFailure();
            }
        }
    }
}
