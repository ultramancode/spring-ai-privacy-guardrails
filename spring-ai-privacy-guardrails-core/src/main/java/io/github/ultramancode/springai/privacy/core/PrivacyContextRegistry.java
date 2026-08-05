package io.github.ultramancode.springai.privacy.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class PrivacyContextRegistry {

    private final ConcurrentMap<PrivacyContextHandle, PrivacyContext> contexts = new ConcurrentHashMap<>();

    PrivacySession openSession() {
        PrivacyContextHandle handle = new PrivacyContextHandle();
        this.contexts.put(handle, new PrivacyContext());
        return new PrivacySession(this, handle);
    }

    PrivacyContext requireActiveContext(PrivacyContextHandle handle) {
        if (handle == null) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.CONTEXT_REQUIRED,
                    PrivacyPhase.SESSION,
                    "Privacy context handle is required"
            );
        }
        PrivacyContext context = this.contexts.get(handle);
        if (context == null) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.CONTEXT_NOT_ACTIVE,
                    PrivacyPhase.SESSION,
                    "Privacy context is unknown or already closed"
            );
        }
        return context;
    }

    void close(PrivacyContextHandle handle) {
        PrivacyContext context = this.contexts.remove(handle);
        if (context != null) {
            context.close();
        }
    }

    boolean isActive(PrivacyContextHandle handle) {
        return handle != null && this.contexts.containsKey(handle);
    }

    int activeSessionCount() {
        return this.contexts.size();
    }
}
