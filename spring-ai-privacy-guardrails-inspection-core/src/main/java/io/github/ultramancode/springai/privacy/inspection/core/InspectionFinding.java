package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * An issue detected in a text segment, identified by category and an inspector-defined code.
 * The matching text is not included. Scores depend on the inspector or model and cannot
 * be compared directly across models.
 *
 * @param segmentId ID of the segment containing the finding
 * @param category what was detected, independent of the detection method
 * @param code inspector-defined machine-readable ID, without source text. It must contain
 *        1-128 ASCII letters, digits, underscores, dots or hyphens and start with a letter or digit
 * @param score non-null optional inspector-specific confidence between zero and one
 */
public record InspectionFinding(String segmentId, Category category, String code, OptionalDouble score) {

    public enum Category {
        /** A prompt attack whose specific type was not determined. */
        PROMPT_ATTACK,
        /** An attempt to override the model's intended instructions. */
        PROMPT_INJECTION,
        /** An attempt to extract hidden prompts or instructions. */
        PROMPT_LEAKING,
        /** Toxic language, such as insults, profanity or threats. */
        TOXICITY,
        /** An application-defined content violation not covered by the other categories. */
        POLICY_VIOLATION
    }

    /** Creates a finding without a confidence score. */
    public InspectionFinding(String segmentId, Category category, String code) {
        this(segmentId, category, code, OptionalDouble.empty());
    }

    /** Creates a finding with a confidence score between zero and one. */
    public InspectionFinding(String segmentId, Category category, String code, double score) {
        this(segmentId, category, code, OptionalDouble.of(score));
    }

    public InspectionFinding {
        InspectionIdentifiers.requireValid(segmentId);
        Objects.requireNonNull(category, "category");
        InspectionIdentifiers.requireValid(code);
        Objects.requireNonNull(score, "score");
        if (score.isPresent()) {
            double value = score.getAsDouble();
            if (!Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("Score must be finite and between zero and one");
            }
        }
    }
}
