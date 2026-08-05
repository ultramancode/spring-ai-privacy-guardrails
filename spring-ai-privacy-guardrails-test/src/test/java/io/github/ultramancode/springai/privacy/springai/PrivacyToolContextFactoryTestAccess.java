package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import org.springframework.ai.chat.model.ToolContext;

/** Test-only bridge for direct wrapper failure-path verification. */
public final class PrivacyToolContextFactoryTestAccess {

    private PrivacyToolContextFactoryTestAccess() {
    }

    public static ToolContext create(PrivacyContextHandle handle) {
        return PrivacyToolContextFactory.create(handle);
    }
}
