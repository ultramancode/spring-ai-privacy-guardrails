package io.github.ultramancode.springai.privacy.core;

import java.util.Objects;

/**
 * Provider-attributed evidence for one detected PII range. Offsets use the
 * source string's UTF-16 indices and the end offset is exclusive. During
 * analyzer ingestion, the entity label is the exact provider-reported value.
 * The core evidence resolver applies provider-specific aliases and trust before
 * the evidence enters a resolved span exposed by {@link PrivacyService}.
 *
 * @param entityType exact uppercase entity label; evidence returned by
 * {@link PrivacyService} contains the resolved canonical type
 * @param start inclusive source-text offset
 * @param end exclusive source-text offset
 * @param provider canonical analyzer provider ID
 * @param score provider-local confidence between {@code 0.0} and {@code 1.0}
 */
public record PiiEvidence(
        String entityType,
        int start,
        int end,
        String provider,
        double score
) {

    /** Validates the entity-label grammar and canonicalizes the provider ID. */
    public PiiEvidence {
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
        provider = PiiProviderId.canonicalize(provider);
    }

    static PiiEvidence from(PiiSpan span, String provider) {
        Objects.requireNonNull(span, "span must not be null");
        return new PiiEvidence(
                span.entityType(),
                span.start(),
                span.end(),
                provider,
                span.score()
        );
    }

    @Override
    public String toString() {
        return "PiiEvidence[provider=" + this.provider + ", start=" + this.start
                + ", end=" + this.end + ", score=" + this.score + "]";
    }
}
