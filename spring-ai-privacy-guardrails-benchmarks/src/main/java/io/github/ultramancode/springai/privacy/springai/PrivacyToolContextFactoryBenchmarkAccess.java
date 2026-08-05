package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import org.springframework.ai.chat.model.ToolContext;

/** Repository-only bridge for measuring the direct tool boundary. */
public final class PrivacyToolContextFactoryBenchmarkAccess {

    private PrivacyToolContextFactoryBenchmarkAccess() {
    }

    public static ToolContext create(PrivacyContextHandle handle) {
        return PrivacyToolContextFactory.create(handle);
    }
}
