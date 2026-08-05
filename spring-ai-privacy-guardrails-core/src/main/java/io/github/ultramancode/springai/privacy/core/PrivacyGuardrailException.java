package io.github.ultramancode.springai.privacy.core;

import java.util.Objects;

/**
 * Indicates that a library-owned privacy operation could not safely continue.
 * Model-provider and application-owned delegate failures normally propagate as
 * their original exception types instead.
 */
public class PrivacyGuardrailException extends RuntimeException {

    private final PrivacyFailureCode code;
    private final PrivacyPhase phase;

    /**
     * Creates a typed privacy failure.
     *
     * @param code stable failure category
     * @param phase privacy-processing phase that produced the failure
     * @param safeMessage non-blank diagnostic message that must not contain
     * sensitive values or untrusted provider details
     */
    public PrivacyGuardrailException(PrivacyFailureCode code, PrivacyPhase phase, String safeMessage) {
        super(safeMessage);
        this.code = Objects.requireNonNull(code, "code must not be null");
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
        if (safeMessage == null || safeMessage.isBlank()) {
            throw new IllegalArgumentException("safeMessage must not be blank");
        }
    }

    /**
     * Returns the stable category suitable for application diagnostics and policy decisions.
     *
     * @return stable privacy failure category
     */
    public PrivacyFailureCode code() {
        return this.code;
    }

    /**
     * Returns the privacy-processing phase in which this failure was created.
     *
     * @return privacy-processing phase
     */
    public PrivacyPhase phase() {
        return this.phase;
    }
}
