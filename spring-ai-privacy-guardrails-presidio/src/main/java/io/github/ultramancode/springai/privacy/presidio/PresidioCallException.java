package io.github.ultramancode.springai.privacy.presidio;

import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailureMetadata;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;

/** Internal sanitized failure used across the Presidio transport and response mapper. */
final class PresidioCallException extends PrivacyGuardrailException
        implements PiiAnalyzerFailureMetadata {

    private final boolean retryable;
    private final int attemptCount;

    PresidioCallException(
            String message,
            boolean retryable,
            PrivacyFailureCode failureCode
    ) {
        this(message, retryable, failureCode, 1);
    }

    PresidioCallException(
            String message,
            boolean retryable,
            PrivacyFailureCode failureCode,
            int attemptCount
    ) {
        super(failureCode, PrivacyPhase.ANALYSIS, message);
        this.retryable = retryable;
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be positive");
        }
        this.attemptCount = attemptCount;
    }

    boolean retryable() {
        return this.retryable;
    }

    PresidioCallException withAttemptCount(int actualAttemptCount) {
        if (this.attemptCount == actualAttemptCount) {
            return this;
        }
        return new PresidioCallException(
                getMessage(),
                this.retryable,
                code(),
                actualAttemptCount
        );
    }

    @Override
    public int attemptCount() {
        return this.attemptCount;
    }
}
