package io.github.ultramancode.springai.privacy.security;

import java.util.concurrent.atomic.AtomicBoolean;

/** Owns removal of one authorization session from its registry. */
final class SecurityToolSession implements AutoCloseable {

    private final SecurityToolSessionRegistry registry;
    private final SecurityToolContextHandle handle;
    private final AtomicBoolean closed = new AtomicBoolean();

    SecurityToolSession(
            SecurityToolSessionRegistry registry,
            SecurityToolContextHandle handle
    ) {
        this.registry = registry;
        this.handle = handle;
    }

    SecurityToolContextHandle handle() {
        return this.handle;
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            this.registry.close(this.handle);
        }
    }
}
