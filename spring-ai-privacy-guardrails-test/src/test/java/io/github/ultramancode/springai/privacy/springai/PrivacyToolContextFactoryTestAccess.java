package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

/** Test-only bridge for direct wrapper failure-path verification. */
public final class PrivacyToolContextFactoryTestAccess {

    private PrivacyToolContextFactoryTestAccess() {
    }

    public static ToolContext create(PrivacyContextHandle handle) {
        return new ToolContext(Map.of(PrivacyRequestContextSupport.CONTEXT_HANDLE, handle));
    }
}
