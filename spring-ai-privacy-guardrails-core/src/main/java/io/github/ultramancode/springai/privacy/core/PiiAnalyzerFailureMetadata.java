package io.github.ultramancode.springai.privacy.core;

/**
 * Optional privacy-safe failure metadata supplied by a PII analyzer adapter.
 * Core uses the metadata only when both values satisfy this contract. If either
 * accessor throws or returns an invalid value, core reports the original
 * analyzer failure with the generic execution category and one attempt instead.
 */
public interface PiiAnalyzerFailureMetadata {

    /**
     * Returns a stable privacy-safe analyzer failure category.
     *
     * @return one of the analyzer-related {@link PrivacyFailureCode} values
     */
    PrivacyFailureCode code();

    /**
     * Returns how many provider attempts completed before failure.
     *
     * @return a positive attempt count
     */
    int attemptCount();
}
