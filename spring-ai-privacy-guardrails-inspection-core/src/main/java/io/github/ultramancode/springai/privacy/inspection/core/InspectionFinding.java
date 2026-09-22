package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.Objects;

/** Provider-specific evidence normalized to a category; no matching text is retained. */
public record InspectionFinding(String segmentId, Category category, String code, Double score) {

    public enum Category {
        PROMPT_ATTACK,
        PROMPT_INJECTION,
        PROMPT_LEAKING,
        RULE_MATCH
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
