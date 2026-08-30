package io.github.ultramancode.springai.privacy.security;

import java.util.UUID;

/** Opaque request-scoped reference. Authentication is never stored in Spring AI ToolContext. */
record SecurityToolContextHandle(UUID value) {

    static SecurityToolContextHandle create() {
        return new SecurityToolContextHandle(UUID.randomUUID());
    }
}
