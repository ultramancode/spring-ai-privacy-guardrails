package io.github.ultramancode.springai.privacy.core;

/**
 * Optional application-provided validation for a candidate selected by a
 * {@link RegexPiiRule} capture group.
 *
 * <p>Implementations are shared across analyses and must therefore be thread-safe.
 * Validator IDs are stable configuration identifiers rather than Spring bean names.
 */
public interface RegexPiiMatchValidator {

    /**
     * Returns the stable lowercase kebab-case ID used by configuration.
     *
     * @return validator ID
     */
    String id();

    /**
     * Returns whether one non-empty capture-group candidate should produce a PII span.
     *
     * @param candidate exact text selected by the rule's capture group
     * @return {@code true} to retain the candidate, or {@code false} to exclude it
     * @throws RuntimeException to report validator failure through the analyzer failure policy
     */
    boolean isValid(String candidate);
}
