package io.github.ultramancode.springai.privacy.core;

import java.util.List;
import java.util.Objects;

/**
 * Canonical PII span produced after resolving one or more pieces of evidence.
 * Offsets use the source string's UTF-16 indices and the end offset is exclusive.
 *
 * @param entityType final canonical entity type selected by the resolution policy
 * @param start inclusive source-text offset
 * @param end exclusive source-text offset
 * @param evidence immutable provider evidence contained by this resolved range
 * @param reason rule that determined the final range and type
 */
public record ResolvedPiiSpan(
        String entityType,
        int start,
        int end,
        List<PiiEvidence> evidence,
        PiiResolutionReason reason
) {

    /** Validates the final range and its supporting evidence. */
    public ResolvedPiiSpan {
        entityType = EntityTypeRegistry.requireValidEntityType(entityType);
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("resolved span positions are invalid");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));
        if (evidence.isEmpty()) {
            throw new IllegalArgumentException("evidence must not be empty");
        }
        for (PiiEvidence item : evidence) {
            if (item.start() < start || item.end() > end) {
                throw new IllegalArgumentException("evidence must be contained by the resolved span");
            }
        }
        reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    @Override
    public String toString() {
        return "ResolvedPiiSpan[entityType=" + this.entityType + ", start=" + this.start + ", end=" + this.end
                + ", evidenceCount=" + this.evidence.size() + ", reason=" + this.reason + "]";
    }
}
