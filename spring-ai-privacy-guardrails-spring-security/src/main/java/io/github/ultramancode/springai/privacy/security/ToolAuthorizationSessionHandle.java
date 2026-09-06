package io.github.ultramancode.springai.privacy.security;

import java.util.UUID;

/**
 * Opaque registry key for a request-scoped tool-authorization session. Authentication is never
 * stored in Spring AI ToolContext.
 */
record ToolAuthorizationSessionHandle(UUID id) {

    static ToolAuthorizationSessionHandle create() {
        return new ToolAuthorizationSessionHandle(UUID.randomUUID());
    }
}
