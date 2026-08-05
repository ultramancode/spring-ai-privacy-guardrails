package io.github.ultramancode.springai.privacy.core;

/** Opaque, non-forgeable handle for a live privacy session context. */
public final class PrivacyContextHandle {

    PrivacyContextHandle() {
    }

    @Override
    public String toString() {
        return "PrivacyContextHandle[opaque]";
    }
}
