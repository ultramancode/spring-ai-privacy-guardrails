package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;

/** Stable signal that an application-facing model or return-direct tool response was blocked. */
public final class PrivacyOutputBlockedException extends PrivacyGuardrailException {

    /**
     * Creates a stable output-policy block signal.
     *
     * @param safeMessage non-sensitive application-facing block message
     */
    public PrivacyOutputBlockedException(String safeMessage) {
        super(PrivacyFailureCode.POLICY_BLOCKED, PrivacyPhase.OUTPUT_POLICY, safeMessage);
    }
}
