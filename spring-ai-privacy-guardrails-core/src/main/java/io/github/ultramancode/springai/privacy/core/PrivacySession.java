package io.github.ultramancode.springai.privacy.core;

/** Auto-closeable lifetime boundary for one isolated privacy context. */
public final class PrivacySession implements AutoCloseable {

    private final PrivacyContextRegistry registry;
    private final PrivacyContextHandle handle;

    PrivacySession(PrivacyContextRegistry registry, PrivacyContextHandle handle) {
        this.registry = registry;
        this.handle = handle;
    }

    /**
     * Returns the opaque handle used to invoke operations within this session.
     * The handle is valid only until {@link #close()} completes.
     *
     * @return this session's non-forgeable context handle
     */
    public PrivacyContextHandle handle() {
        return this.handle;
    }

    /** Removes the session mapping from the owning service; repeated calls are safe. */
    @Override
    public void close() {
        this.registry.close(this.handle);
    }
}
