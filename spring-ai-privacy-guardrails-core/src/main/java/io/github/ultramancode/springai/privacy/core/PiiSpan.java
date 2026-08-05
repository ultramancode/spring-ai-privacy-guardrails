package io.github.ultramancode.springai.privacy.core;

/**
 * A validated PII range and confidence score reported by an analyzer or caller.
 * Offsets use the source string's UTF-16 indices and the end offset is exclusive.
 *
 * @param entityType reported entity type using the canonical uppercase label grammar
 * @param start inclusive source-text offset
 * @param end exclusive source-text offset
 * @param score analyzer confidence between {@code 0.0} and {@code 1.0}
 */
public record PiiSpan(
        String entityType,
        int start,
        int end,
        double score
) {

    /** Validates a reported source span. */
    public PiiSpan {
        entityType = EntityTypeRegistry.requireValidEntityType(entityType);
        if (start < 0) {
            throw new IllegalArgumentException("start must be >= 0");
        }
        if (end <= start) {
            throw new IllegalArgumentException("end must be > start");
        }
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw new IllegalArgumentException("score must be between 0.0 and 1.0");
        }
    }

    @Override
    public String toString() {
        return "PiiSpan[start=" + this.start + ", end=" + this.end + ", score=" + this.score + "]";
    }
}
