package io.github.ultramancode.springai.privacy.security;

/** Removes one tool-authorization session from its registry when closed. */
final class ToolAuthorizationSession implements AutoCloseable {

    private final ToolAuthorizationSessionRegistry sessionRegistry;
    private final ToolAuthorizationSessionHandle handle;

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
        this.sessionRegistry.close(this.handle);
    }
}
