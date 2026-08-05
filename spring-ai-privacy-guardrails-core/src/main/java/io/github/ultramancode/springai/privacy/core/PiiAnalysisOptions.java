package io.github.ultramancode.springai.privacy.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Language, entity allowlist, and global score threshold used for one PII analysis.
 *
 * @param language analyzer language code in canonical lowercase form
 * @param includedEntityTypes exact uppercase canonical entity-type allowlist; an empty list
 * requests every type available from the configured analyzers
 * @param minimumScore global minimum confidence accepted from any analyzer, between
 * {@code 0.0} and {@code 1.0}
 */
public record PiiAnalysisOptions(
        String language,
        List<String> includedEntityTypes,
        double minimumScore
) {

    private static final int MAX_LANGUAGE_CODE_LENGTH = 64;
    private static final Pattern LANGUAGE_CODE_PATTERN = Pattern.compile(
            "[A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*"
    );

    /** Default analyzer language. */
    public static final String DEFAULT_LANGUAGE = "en";

    /** Default global minimum confidence, which accepts every valid analyzer score. */
    public static final double DEFAULT_MINIMUM_SCORE = 0.0;

    /** Validates analysis options and canonicalizes the language code. */
    public PiiAnalysisOptions {
        language = canonicalizeLanguageCode(language);
        includedEntityTypes = requireValidIncludedEntityTypes(includedEntityTypes);
        if (!Double.isFinite(minimumScore) || minimumScore < 0.0 || minimumScore > 1.0) {
            throw new IllegalArgumentException("minimumScore must be between 0.0 and 1.0");
        }
    }

    /**
     * Returns options using the default language, no entity filter, and no
     * additional confidence threshold.
     *
     * @return default analysis options
     */
    public static PiiAnalysisOptions defaults() {
        return new PiiAnalysisOptions(DEFAULT_LANGUAGE, List.of(), DEFAULT_MINIMUM_SCORE);
    }

    /**
     * Creates a builder initialized with the default analysis options.
     *
     * @return a new options builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validates a 1-to-64-character provider-neutral language code composed of
     * ASCII alphanumeric segments separated by single hyphens or underscores,
     * then returns its lowercase canonical representation. ASCII letter case is
     * insignificant; malformed separators and punctuation are not repaired.
     *
     * @param language language code to validate
     * @return canonical lowercase language code
     */
    public static String canonicalizeLanguageCode(String language) {
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("language must not be blank");
        }
        if (language.length() > MAX_LANGUAGE_CODE_LENGTH) {
            throw new IllegalArgumentException("language is too long");
        }
        if (!LANGUAGE_CODE_PATTERN.matcher(language).matches()) {
            throw new IllegalArgumentException(
                    "language must use ASCII letters and digits separated by single hyphens or underscores"
            );
        }
        return language.toLowerCase(Locale.ROOT);
    }

    private static List<String> requireValidIncludedEntityTypes(List<String> includedEntityTypes) {
        Set<String> configuredTypes = new LinkedHashSet<>();
        for (String entityType : Objects.requireNonNull(
                includedEntityTypes,
                "includedEntityTypes must not be null"
        )) {
            if (entityType == null || entityType.isBlank()) {
                throw new IllegalArgumentException("includedEntityTypes must not contain blank values");
            }
            if (!configuredTypes.add(EntityTypeRegistry.requireValidEntityType(entityType))) {
                throw new IllegalArgumentException("includedEntityTypes contain duplicates");
            }
        }
        return List.copyOf(configuredTypes);
    }

    /** Builds immutable {@link PiiAnalysisOptions} instances. */
    public static final class Builder {

        private String language = DEFAULT_LANGUAGE;
        private List<String> includedEntityTypes = List.of();
        private double minimumScore = DEFAULT_MINIMUM_SCORE;

        private Builder() {
        }

        /**
         * Selects the analyzer language.
         *
         * @param language analyzer language code accepted by
         * {@link #canonicalizeLanguageCode(String)}
         * @return this builder
         */
        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /**
         * Selects the entity types to retain after canonicalization.
         *
         * @param includedEntityTypes exact uppercase entity-type allowlist, or an empty list for all types
         * @return this builder
         */
        public Builder includedEntityTypes(List<String> includedEntityTypes) {
            this.includedEntityTypes = includedEntityTypes;
            return this;
        }

        /**
         * Sets the global minimum analyzer confidence.
         *
         * @param minimumScore value between {@code 0.0} and {@code 1.0}
         * @return this builder
         */
        public Builder minimumScore(double minimumScore) {
            this.minimumScore = minimumScore;
            return this;
        }

        /**
         * Validates the accumulated values and creates immutable options.
         *
         * @return validated analysis options
         */
        public PiiAnalysisOptions build() {
            return new PiiAnalysisOptions(this.language, this.includedEntityTypes, this.minimumScore);
        }
    }
}
