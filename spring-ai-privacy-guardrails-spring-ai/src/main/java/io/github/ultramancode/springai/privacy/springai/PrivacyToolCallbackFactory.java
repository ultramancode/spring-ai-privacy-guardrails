package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Creates a privacy-aware wrapper for a Spring AI tool callback. Every callback supplied
 * to a privacy-guarded {@code ChatClient} must be wrapped by this factory; Spring AI does
 * not expose a safe global hook that can retroactively protect raw callbacks.
 */
public final class PrivacyToolCallbackFactory {

    private final PrivacyService privacyService;
    private final ToolDisclosurePolicy disclosurePolicy;
    private final Provenance provenance = new Provenance();

    /**
     * Creates a factory bound to one privacy service and disclosure policy.
     *
     * @param privacyService service that owns request sessions and transformations
     * @param disclosurePolicy least-privilege decision applied independently to each tool
     */
    public PrivacyToolCallbackFactory(
            PrivacyService privacyService,
            ToolDisclosurePolicy disclosurePolicy
    ) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
        this.disclosurePolicy = Objects.requireNonNull(
                disclosurePolicy,
                "disclosurePolicy must not be null"
        );
    }

    /**
     * Returns the only callback instance that should be registered with Spring AI.
     *
     * @param toolCallback original application callback
     * @return privacy-wrapped callback bound to this factory
     */
    public ToolCallback wrap(ToolCallback toolCallback) {
        return wrapValidated(requireUnwrapped(toolCallback));
    }

    /**
     * Returns whether the callback was created by this exact factory instance.
     * This semantic check avoids exposing the private wrapper implementation.
     *
     * @param toolCallback callback to inspect
     * @return {@code true} only for a wrapper created by this factory
     */
    public boolean isWrapped(ToolCallback toolCallback) {
        return toolCallback instanceof PrivacyToolCallbackWrapper wrapper
                && wrapper.hasFactoryProvenance(this.provenance);
    }

    /**
     * Wraps callbacks in their iteration order and returns an immutable registration list.
     * The complete batch is checked for null or already-wrapped callbacks before any wrapper
     * is created, and duplicate tool names are rejected before registration.
     *
     * @param toolCallbacks original application callbacks
     * @return immutable privacy-wrapped callbacks in iteration order
     */
    public List<ToolCallback> wrapAll(Collection<? extends ToolCallback> toolCallbacks) {
        Collection<? extends ToolCallback> callbacks = Objects.requireNonNull(
                toolCallbacks,
                "toolCallbacks must not be null"
        );
        List<ToolCallback> delegates = new ArrayList<>(callbacks.size());
        for (ToolCallback callback : callbacks) {
            delegates.add(requireUnwrapped(callback));
        }
        List<ToolCallback> wrapped = delegates.stream().map(this::wrapValidated).toList();
        requireDistinctToolNames(wrapped);
        return wrapped;
    }

    /**
     * Wraps callbacks in argument order and returns an immutable registration list.
     *
     * @param toolCallbacks original application callbacks
     * @return immutable privacy-wrapped callbacks in argument order
     */
    public List<ToolCallback> wrapAll(ToolCallback... toolCallbacks) {
        ToolCallback[] callbacks = Objects.requireNonNull(toolCallbacks, "toolCallbacks must not be null");
        return wrapAll(Arrays.asList(callbacks));
    }

    /**
     * Wraps every callback supplied by a dynamic provider while preserving provider
     * refresh behavior. Spring AI invokes the returned provider for each request, so
     * a changed MCP or application tool list is protected on its next resolution. The
     * source provider must return original, unwrapped callbacks. Source exceptions
     * propagate unchanged; null snapshots or elements become safe contract failures.
     *
     * @param toolCallbackProvider dynamic provider of original callbacks
     * @return dynamic provider that privacy-wraps each current callback snapshot
     */
    public ToolCallbackProvider wrapProvider(ToolCallbackProvider toolCallbackProvider) {
        return new PrivacyToolCallbackProvider(List.of(requireUnwrappedProvider(toolCallbackProvider)), this);
    }

    /**
     * Combines dynamic providers in argument order and wraps their complete current
     * callback set as one registration. Cross-provider duplicate names therefore fail
     * inside this privacy boundary before Spring AI validates the combined list. Every
     * source must return original, unwrapped callbacks. Source exceptions propagate
     * unchanged; null snapshots or elements become safe contract failures.
     *
     * @param toolCallbackProviders dynamic providers to combine in argument order
     * @return one dynamic provider of privacy-wrapped callbacks
     */
    public ToolCallbackProvider wrapProviders(ToolCallbackProvider... toolCallbackProviders) {
        ToolCallbackProvider[] providers = Objects.requireNonNull(
                toolCallbackProviders,
                "toolCallbackProviders must not be null"
        );
        if (providers.length == 0) {
            throw new IllegalArgumentException("toolCallbackProviders must not be empty");
        }
        List<ToolCallbackProvider> delegates = new ArrayList<>(providers.length);
        for (ToolCallbackProvider provider : providers) {
            delegates.add(requireUnwrappedProvider(provider));
        }
        return new PrivacyToolCallbackProvider(delegates, this);
    }

    private ToolCallback requireUnwrapped(ToolCallback toolCallback) {
        ToolCallback delegate = Objects.requireNonNull(toolCallback, "toolCallback must not be null");
        if (delegate instanceof PrivacyToolCallbackWrapper) {
            throw new IllegalArgumentException("toolCallback must not already be privacy wrapped");
        }
        return delegate;
    }

    private ToolCallbackProvider requireUnwrappedProvider(ToolCallbackProvider toolCallbackProvider) {
        ToolCallbackProvider delegate = Objects.requireNonNull(
                toolCallbackProvider,
                "toolCallbackProvider must not be null"
        );
        if (delegate instanceof PrivacyToolCallbackProvider) {
            throw new IllegalArgumentException("toolCallbackProvider must not already be privacy wrapped");
        }
        return delegate;
    }

    private ToolCallback wrapValidated(ToolCallback delegate) {
        return new PrivacyToolCallbackWrapper(
                delegate,
                this.privacyService,
                this.disclosurePolicy,
                this.provenance
        );
    }

    boolean usesPrivacyService(PrivacyService expected) {
        return this.privacyService == expected;
    }

    Provenance provenance() {
        return this.provenance;
    }

    private void requireDistinctToolNames(List<ToolCallback> callbacks) {
        Set<String> names = new HashSet<>();
        for (ToolCallback callback : callbacks) {
            if (!names.add(callback.getToolDefinition().name())) {
                throw new IllegalArgumentException("toolCallbacks must have distinct tool names");
            }
        }
    }

    static final class Provenance {

        private Provenance() {
        }
    }

}
