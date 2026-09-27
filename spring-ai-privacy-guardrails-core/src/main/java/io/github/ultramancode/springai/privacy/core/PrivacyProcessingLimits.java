package io.github.ultramancode.springai.privacy.core;

/**
 * Per-service limits for privacy processing. Character counts use UTF-16
 * code units, as reported by {@link String#length()}. All limits must be positive.
 * Use {@link #builder()} to override selected defaults.
 *
 * <p>For text and JSON, the output limit covers the complete returned text. For
 * direct value-tree operations, it covers the sum of string values and map keys.
 * Nodes include containers, scalar values, and map keys.</p>
 *
 * @param maxTextCharacters maximum length of one text or boundary payload, or the combined
 *                          length of texts in one batch analysis
 * @param maxOutputCharacters maximum character count of the complete result of a transformation,
 *                            including unchanged portions
 * @param maxValueTreeCharacters maximum combined string, key, and numeric representation length
 *                              in a value tree or JSON document
 * @param maxValueTreeNodes maximum node count in a value tree or JSON document
 * @param maxDepth maximum container nesting depth in a value tree or JSON document,
 *                 counting the outermost container as depth one
 * @param maxAnalysisSegments maximum number of independent texts in one batch analysis
 * @param maxResultSpans maximum number of spans collected in one analysis operation
 */
public record PrivacyProcessingLimits(
        int maxTextCharacters,
        int maxOutputCharacters,
        int maxValueTreeCharacters,
        int maxValueTreeNodes,
        int maxDepth,
        int maxAnalysisSegments,
        int maxResultSpans
) {

    /** Default maximum text or payload length, in UTF-16 code units. */
    public static final int DEFAULT_MAX_TEXT_CHARACTERS = 1_000_000;
    /** Default maximum output character count, in UTF-16 code units. */
    public static final int DEFAULT_MAX_OUTPUT_CHARACTERS = 8_000_000;
    /** Default maximum combined string, key, and numeric representation length. */
    public static final int DEFAULT_MAX_VALUE_TREE_CHARACTERS = 1_000_000;
    /** Default maximum value-tree or JSON node count, including containers and keys. */
    public static final int DEFAULT_MAX_VALUE_TREE_NODES = 100_000;
    /** Default maximum container nesting depth. */
    public static final int DEFAULT_MAX_DEPTH = 128;
    /** Default maximum number of independent texts in one batch analysis. */
    public static final int DEFAULT_MAX_ANALYSIS_SEGMENTS = 100_000;
    /** Default maximum number of spans in one analysis operation. */
    public static final int DEFAULT_MAX_RESULT_SPANS = 100_000;

    private static final PrivacyProcessingLimits DEFAULTS = new PrivacyProcessingLimits(
            DEFAULT_MAX_TEXT_CHARACTERS,
            DEFAULT_MAX_OUTPUT_CHARACTERS,
            DEFAULT_MAX_VALUE_TREE_CHARACTERS,
            DEFAULT_MAX_VALUE_TREE_NODES,
            DEFAULT_MAX_DEPTH,
            DEFAULT_MAX_ANALYSIS_SEGMENTS,
            DEFAULT_MAX_RESULT_SPANS
    );

    /**
     * Validates the processing limits.
     *
     * @throws IllegalArgumentException if any limit is zero or negative
     */
    public PrivacyProcessingLimits {
        requirePositive(maxTextCharacters, "maxTextCharacters");
        requirePositive(maxOutputCharacters, "maxOutputCharacters");
        requirePositive(maxValueTreeCharacters, "maxValueTreeCharacters");
        requirePositive(maxValueTreeNodes, "maxValueTreeNodes");
        requirePositive(maxDepth, "maxDepth");
        requirePositive(maxAnalysisSegments, "maxAnalysisSegments");
        requirePositive(maxResultSpans, "maxResultSpans");
    }

    /**
     * Returns the default processing limits.
     *
     * @return the shared immutable default limits
     */
    public static PrivacyProcessingLimits defaults() {
        return DEFAULTS;
    }

    /**
     * Creates a builder initialized with the default processing limits.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    /** Builds immutable {@link PrivacyProcessingLimits} instances. */
    public static final class Builder {

        private int maxTextCharacters = DEFAULT_MAX_TEXT_CHARACTERS;
        private int maxOutputCharacters = DEFAULT_MAX_OUTPUT_CHARACTERS;
        private int maxValueTreeCharacters = DEFAULT_MAX_VALUE_TREE_CHARACTERS;
        private int maxValueTreeNodes = DEFAULT_MAX_VALUE_TREE_NODES;
        private int maxDepth = DEFAULT_MAX_DEPTH;
        private int maxAnalysisSegments = DEFAULT_MAX_ANALYSIS_SEGMENTS;
        private int maxResultSpans = DEFAULT_MAX_RESULT_SPANS;

        private Builder() {
        }

        /** Sets the maximum UTF-16 length of one text, boundary payload, or batch of texts. */
        public Builder maxTextCharacters(int maxTextCharacters) {
            this.maxTextCharacters = maxTextCharacters;
            return this;
        }

        /** Sets the maximum UTF-16 output length per transformation, including unchanged text. */
        public Builder maxOutputCharacters(int maxOutputCharacters) {
            this.maxOutputCharacters = maxOutputCharacters;
            return this;
        }

        /** Sets the maximum combined UTF-16 length of strings, keys, and numeric representations. */
        public Builder maxValueTreeCharacters(int maxValueTreeCharacters) {
            this.maxValueTreeCharacters = maxValueTreeCharacters;
            return this;
        }

        /** Sets the maximum node count, including containers, scalar values, and map keys. */
        public Builder maxValueTreeNodes(int maxValueTreeNodes) {
            this.maxValueTreeNodes = maxValueTreeNodes;
            return this;
        }

        /** Sets the maximum container nesting depth, counting the outermost container as depth one. */
        public Builder maxDepth(int maxDepth) {
            this.maxDepth = maxDepth;
            return this;
        }

        /** Sets the maximum number of independent texts in one batch analysis. */
        public Builder maxAnalysisSegments(int maxAnalysisSegments) {
            this.maxAnalysisSegments = maxAnalysisSegments;
            return this;
        }

        /** Sets the maximum number of spans collected in one analysis operation. */
        public Builder maxResultSpans(int maxResultSpans) {
            this.maxResultSpans = maxResultSpans;
            return this;
        }

        /**
         * Validates the configured values and creates immutable processing limits.
         *
         * @return validated processing limits
         * @throws IllegalArgumentException if any limit is zero or negative
         */
        public PrivacyProcessingLimits build() {
            return new PrivacyProcessingLimits(
                    this.maxTextCharacters,
                    this.maxOutputCharacters,
                    this.maxValueTreeCharacters,
                    this.maxValueTreeNodes,
                    this.maxDepth,
                    this.maxAnalysisSegments,
                    this.maxResultSpans
            );
        }
    }
}
