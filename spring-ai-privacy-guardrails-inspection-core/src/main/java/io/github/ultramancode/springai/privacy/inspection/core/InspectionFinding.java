package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.Objects;

/**
 * Inspector-specific evidence normalized to a category. No matching text is retained.
 * A non-null score is local to the inspector/model and is not comparable across models.
 *
 * @param segmentId ID of the segment containing the finding
 * @param category what was detected, independent of the detection method
 * @param code inspector-defined machine-readable ID, without source text. It must contain
 *        1-128 ASCII letters, digits, underscores, dots or hyphens and start with a letter or digit
 * @param score optional inspector-specific confidence between zero and one
 */
public record InspectionFinding(String segmentId, Category category, String code, Double score) {

    public enum Category {
        /** A prompt attack whose specific type was not determined. */
        PROMPT_ATTACK,
        /** An attempt to override the model's intended instructions. */
        PROMPT_INJECTION,
        /** An attempt to extract hidden prompts or instructions. */
        PROMPT_LEAKING,
        /** An application-defined content violation outside the prompt-attack categories. */
        POLICY_VIOLATION
    }

    public InspectionFinding {
        ContentSegment.requireIdentifier(segmentId);
        Objects.requireNonNull(category, "category");
        ContentSegment.requireIdentifier(code);
        if (score != null && (!Double.isFinite(score) || score < 0 || score > 1)) {
            throw new IllegalArgumentException("Score must be finite and between zero and one");
        }
    }
}
