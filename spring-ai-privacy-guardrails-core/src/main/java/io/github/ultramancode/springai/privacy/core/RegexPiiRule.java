package io.github.ultramancode.springai.privacy.core;

/**
 * Configuration for one regular-expression PII detection rule.
 * Patterns are trusted application configuration executed by the JDK regex
 * engine. Avoid nested or ambiguous quantifiers whose backtracking cost can
 * grow disproportionately with attacker-controlled input.
 *
 * @param entityType canonical uppercase entity type assigned to each match
 * @param pattern Java regular expression evaluated against the complete source text
 * @param score confidence assigned to every match, between {@code 0.0} and {@code 1.0}
 * @param captureGroup capture group whose range becomes the PII span; {@code 0} selects
 * the complete match
 * @param matchValidator optional validator applied to the selected capture-group candidate;
 * {@code null} preserves format-only matching
 */
public record RegexPiiRule(
        String entityType,
        String pattern,
        double score,
        int captureGroup,
        RegexPiiMatchValidator matchValidator
) {

    /** Default confidence score used by configuration binding when none is supplied. */
    public static final double DEFAULT_SCORE = 0.85;

    /**
     * Creates a rule without a match validator.
     *
     * @param entityType canonical uppercase entity type assigned to each match
     * @param pattern Java regular expression evaluated against the complete source text
     * @param score confidence assigned to every match
     * @param captureGroup capture group whose range becomes the PII span
     */
    public RegexPiiRule(String entityType, String pattern, double score, int captureGroup) {
        this(entityType, pattern, score, captureGroup, null);
    }

    /** Validates one regex rule. */
    public RegexPiiRule {
        entityType = EntityTypeRegistry.requireValidEntityType(entityType);
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("pattern must not be blank");
        }
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0.0 and 1.0");
        }
        if (captureGroup < 0) {
            throw new IllegalArgumentException("captureGroup must be >= 0");
        }
    }
}
