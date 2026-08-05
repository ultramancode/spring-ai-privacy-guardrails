package io.github.ultramancode.springai.privacy.core;

import java.util.Objects;

/** Removes untrusted failure details at privacy boundaries while preserving fatal JVM errors. */
public final class PrivacyFailureSanitizer {

    private PrivacyFailureSanitizer() {
    }

    /**
     * Replaces an untrusted failure with a new privacy-safe exception that does
     * not retain the original message, cause, or stack metadata.
     *
     * @param failure untrusted failure to discard after fatal-error checking
     * @param code stable privacy failure category
     * @param phase phase in which the safe failure is reported
     * @param safeMessage non-sensitive message controlled by the library or application
     * @return a new sanitized privacy exception
     */
    public static PrivacyGuardrailException sanitize(
            Throwable failure,
            PrivacyFailureCode code,
            PrivacyPhase phase,
            String safeMessage
    ) {
        rethrowIfFatal(failure);
        Objects.requireNonNull(failure, "failure must not be null");
        return new PrivacyGuardrailException(code, phase, safeMessage);
    }

    /**
     * Rethrows fatal JVM errors and otherwise returns normally. Boundary code can
     * call this before sanitizing or observing an arbitrary {@link Throwable}.
     *
     * @param failure failure to classify
     */
    @SuppressWarnings("removal")
    public static void rethrowIfFatal(Throwable failure) {
        Objects.requireNonNull(failure, "failure must not be null");
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
        if (failure instanceof LinkageError fatal) {
            throw fatal;
        }
    }
}
