package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Owns opaque session metadata and removes all privacy internals at request boundaries. */
final class PrivacyRequestContextSupport {

    static final String CONTEXT_HANDLE = "io.github.ultramancode.springai.privacy.context-handle";
    private static final String CONTEXT_LIFECYCLE_MARKER =
            "io.github.ultramancode.springai.privacy.lifecycle-marker";

    private PrivacyRequestContextSupport() {
    }

    static Optional<PrivacyContextHandle> findHandle(ChatClientRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return findHandle(request.context());
    }

    static Optional<PrivacyContextHandle> findHandle(Map<String, Object> context) {
        Objects.requireNonNull(context, "context must not be null");
        if (!context.containsKey(CONTEXT_HANDLE)) {
            return Optional.empty();
        }
        return Optional.of(handleFrom(context.get(CONTEXT_HANDLE)));
    }

    static ChatClientRequest attachHandle(ChatClientRequest request, PrivacyContextHandle handle) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(handle, "handle must not be null");
        Map<String, Object> requestContext = new HashMap<>(request.context());
        requestContext.put(CONTEXT_HANDLE, handle);

        Prompt prompt = request.prompt();
        ChatOptions options = attachToToolOptions(prompt.getOptions(), handle);
        Prompt updatedPrompt = options == prompt.getOptions()
                ? prompt
                : new Prompt(prompt.getInstructions(), options);
        return request.mutate().prompt(updatedPrompt).context(requestContext).build();
    }

    static ChatClientRequest attachLifecycle(ChatClientRequest request, PrivacyContextHandle handle) {
        ChatClientRequest attached = attachHandle(request, handle);
        Map<String, Object> context = new HashMap<>(attached.context());
        context.put(CONTEXT_LIFECYCLE_MARKER, new LifecycleMarker(handle));
        return attached.mutate().context(context).build();
    }

    static void requireLifecycle(ChatClientRequest request, String componentName) {
        Objects.requireNonNull(request, "request must not be null");
        Object marker = request.context().get(CONTEXT_LIFECYCLE_MARKER);
        PrivacyContextHandle handle = findHandle(request).orElse(null);
        if (!(marker instanceof LifecycleMarker lifecycleMarker)
                || lifecycleMarker.handle() != handle) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.CONTEXT_REQUIRED,
                    PrivacyPhase.SESSION,
                    componentName + " requires PrivacyLifecycleAdvisor"
            );
        }
    }

    static ChatClientResponse stripInternalPrivacyEntries(ChatClientResponse response) {
        Objects.requireNonNull(response, "response must not be null");
        if (!response.context().containsKey(CONTEXT_HANDLE)
                && !response.context().containsKey(CONTEXT_LIFECYCLE_MARKER)
                && !PrivacyOutputContextSupport.hasInternalEntries(response.context())
                && !PrivacyToolExecutionContextSupport.hasInternalEntries(response.context())) {
            return response;
        }
        Map<String, Object> context = new HashMap<>(response.context());
        context.remove(CONTEXT_HANDLE);
        context.remove(CONTEXT_LIFECYCLE_MARKER);
        PrivacyOutputContextSupport.removeInternalEntries(context);
        PrivacyToolExecutionContextSupport.removeInternalEntries(context);
        return new ChatClientResponse(response.chatResponse(), context);
    }

    static Map<String, Object> stripInternalPrivacyEntries(Map<String, Object> context) {
        Objects.requireNonNull(context, "context must not be null");
        Map<String, Object> applicationContext = new HashMap<>(context);
        applicationContext.remove(CONTEXT_HANDLE);
        applicationContext.remove(CONTEXT_LIFECYCLE_MARKER);
        PrivacyOutputContextSupport.removeInternalEntries(applicationContext);
        PrivacyToolExecutionContextSupport.removeInternalEntries(applicationContext);
        return applicationContext;
    }

    private static ChatOptions attachToToolOptions(
            ChatOptions options,
            PrivacyContextHandle handle
    ) {
        if (!(options instanceof ToolCallingChatOptions toolCallingOptions)) {
            return options;
        }
        Map<String, Object> toolContext = new HashMap<>();
        if (toolCallingOptions.getToolContext() != null) {
            toolContext.putAll(toolCallingOptions.getToolContext());
        }
        toolContext.put(CONTEXT_HANDLE, handle);
        return toolCallingOptions.mutate().toolContext(toolContext).build();
    }

    private static PrivacyContextHandle handleFrom(Object contextValue) {
        if (contextValue instanceof PrivacyContextHandle handle) {
            return handle;
        }
        throw new PrivacyGuardrailException(
                PrivacyFailureCode.CONTEXT_REQUIRED,
                PrivacyPhase.SESSION,
                "Privacy context handle is invalid"
        );
    }

    private record LifecycleMarker(PrivacyContextHandle handle) {
    }
}
