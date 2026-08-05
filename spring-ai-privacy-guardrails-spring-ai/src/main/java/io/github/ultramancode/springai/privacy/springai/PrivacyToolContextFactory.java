package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import org.springframework.ai.chat.model.ToolContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Creates Spring AI tool contexts carrying a privacy session handle. */
final class PrivacyToolContextFactory {

    private PrivacyToolContextFactory() {
    }

    static ToolContext create(PrivacyContextHandle handle) {
        return create(handle, Map.of());
    }

    static ToolContext create(PrivacyContextHandle handle, Map<String, Object> existingContext) {
        Objects.requireNonNull(handle, "handle must not be null");
        Objects.requireNonNull(existingContext, "existingContext must not be null");
        Map<String, Object> context = new HashMap<>(existingContext);
        context.put(PrivacyRequestContextSupport.CONTEXT_HANDLE, handle);
        return new ToolContext(context);
    }
}
