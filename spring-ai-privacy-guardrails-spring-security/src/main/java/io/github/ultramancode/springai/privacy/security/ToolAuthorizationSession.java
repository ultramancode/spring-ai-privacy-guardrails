package io.github.ultramancode.springai.privacy.security;

import java.util.concurrent.atomic.AtomicBoolean;

/** Removes one tool-authorization session from its registry when closed. */
final class ToolAuthorizationSession implements AutoCloseable {

    private final ToolAuthorizationSessionRegistry sessionRegistry;
    private final ToolAuthorizationSessionHandle handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    ToolAuthorizationSession(
            ToolAuthorizationSessionRegistry sessionRegistry,
            ToolAuthorizationSessionHandle handle
    ) {
        this.sessionRegistry = sessionRegistry;
        this.handle = handle;
    }

    ToolAuthorizationSessionHandle handle() {
        return this.handle;
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.sessionRegistry.close(this.handle);
        }
    }
}
