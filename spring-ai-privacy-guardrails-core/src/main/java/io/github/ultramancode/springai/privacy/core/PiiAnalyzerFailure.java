package io.github.ultramancode.springai.privacy.core;

import java.util.Objects;

/**
 * Sanitized, low-cardinality analyzer failure metadata. Exception messages,
 * causes, stack traces, and implementation class names are deliberately excluded.
 *
 * @param provider canonical analyzer provider ID
 * @param code stable privacy-safe failure category
 * @param phase stable processing phase
 * @param attemptCount number of attempts made by the provider adapter
 */
public record PiiAnalyzerFailure(
        String provider,
        PrivacyFailureCode code,
        PrivacyPhase phase,
        int attemptCount
) {

    /** Validates and canonicalizes sanitized analyzer failure metadata. */
    public PiiAnalyzerFailure {
        provider = PiiProviderId.canonicalize(provider);
        code = Objects.requireNonNull(code, "code must not be null");
        if (!isAnalyzerFailureCode(code)) {
            throw new IllegalArgumentException("code must describe an analyzer failure");
        }
        phase = Objects.requireNonNull(phase, "phase must not be null");
        if (phase != PrivacyPhase.ANALYSIS) {
            throw new IllegalArgumentException("analyzer failure phase must be ANALYSIS");
        }
        if (attemptCount < 1) {
            throw new IllegalArgumentException("attemptCount must be >= 1");
        }
    }

    static PiiAnalyzerFailure executionFailure(String provider, Throwable exception) {
        if (exception == null) {
            throw new IllegalArgumentException("exception must not be null");
        }
        PrivacyFailureCode code = PrivacyFailureCode.ANALYZER_EXECUTION_FAILED;
        int attemptCount = 1;
        if (exception instanceof PiiAnalyzerFailureMetadata metadata) {
            try {
                PrivacyFailureCode metadataCode = metadata.code();
                int metadataAttemptCount = metadata.attemptCount();
                if (isAnalyzerFailureCode(metadataCode) && metadataAttemptCount >= 1) {
                    code = metadataCode;
                    attemptCount = metadataAttemptCount;
                }
            } catch (Throwable metadataFailure) {
                PrivacyFailureSanitizer.rethrowIfFatal(metadataFailure);
            }
        } else if (exception instanceof PrivacyGuardrailException guardrailFailure
                && guardrailFailure.phase() == PrivacyPhase.ANALYSIS
                && isAnalyzerFailureCode(guardrailFailure.code())) {
            code = guardrailFailure.code();
        }
        return new PiiAnalyzerFailure(provider, code, PrivacyPhase.ANALYSIS, attemptCount);
    }

    static PiiAnalyzerFailure contractViolation(String provider) {
        return new PiiAnalyzerFailure(
                provider,
                PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION,
                PrivacyPhase.ANALYSIS,
                1
        );
    }

    private static boolean isAnalyzerFailureCode(PrivacyFailureCode code) {
        return code == PrivacyFailureCode.ANALYZER_EXECUTION_FAILED
                || code == PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION
                || code == PrivacyFailureCode.ANALYZER_UNAVAILABLE
                || code == PrivacyFailureCode.ANALYZER_TIMEOUT
                || code == PrivacyFailureCode.ANALYZER_AUTHENTICATION_FAILED
                || code == PrivacyFailureCode.ANALYZER_RATE_LIMITED
                || code == PrivacyFailureCode.ANALYZER_RESPONSE_INVALID;
    }
}
