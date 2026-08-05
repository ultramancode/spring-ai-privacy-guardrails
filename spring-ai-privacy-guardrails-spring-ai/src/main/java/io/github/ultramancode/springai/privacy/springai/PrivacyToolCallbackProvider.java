package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Resolves the current delegate callbacks and protects that request's complete snapshot. */
final class PrivacyToolCallbackProvider implements ToolCallbackProvider {

    private final List<ToolCallbackProvider> sourceProviders;
    private final PrivacyToolCallbackFactory factory;

    PrivacyToolCallbackProvider(
            List<ToolCallbackProvider> sourceProviders,
            PrivacyToolCallbackFactory factory
    ) {
        this.sourceProviders = List.copyOf(
                Objects.requireNonNull(sourceProviders, "sourceProviders must not be null")
        );
        if (this.sourceProviders.isEmpty()) {
            throw new IllegalArgumentException("sourceProviders must not be empty");
        }
        this.factory = Objects.requireNonNull(factory, "factory must not be null");
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ToolCallbackProvider sourceProvider : this.sourceProviders) {
            ToolCallback[] current = sourceProvider.getToolCallbacks();
            if (current == null) {
                throw providerContractViolation();
            }
            for (ToolCallback callback : current) {
                if (callback == null) {
                    throw providerContractViolation();
                }
                callbacks.add(callback);
            }
        }
        return this.factory.wrapAll(callbacks).toArray(ToolCallback[]::new);
    }

    private static PrivacyGuardrailException providerContractViolation() {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.TOOL_PROVIDER_UNAVAILABLE,
                PrivacyPhase.TOOL_INPUT,
                "Tool callback provider returned an invalid callback snapshot"
        );
    }
}
