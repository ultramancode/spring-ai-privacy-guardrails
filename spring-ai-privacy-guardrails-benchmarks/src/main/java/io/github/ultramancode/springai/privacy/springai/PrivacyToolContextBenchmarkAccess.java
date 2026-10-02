package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

/** Repository-only bridge for measuring the direct tool boundary. */
public final class PrivacyToolContextBenchmarkAccess {

    private PrivacyToolContextBenchmarkAccess() {
    }

    public static ToolContext create(PrivacyContextHandle handle) {
        return new ToolContext(Map.of(PrivacyRequestContextSupport.CONTEXT_HANDLE, handle));
    }
}
