package io.github.ultramancode.springai.privacy.springai;

import java.util.Objects;

/**
 * Privacy-safe runtime enforcement event. The contract intentionally contains only
 * a boundary and outcome: it carries no payload, PII, token, entity type, tool name,
 * request identifier, or correlation data.
 *
 * @param boundary supported boundary that completed the decision
 * @param outcome high-level enforcement outcome
 */
public record PrivacyEnforcementEvent(
        PrivacyEnforcementBoundary boundary,
        PrivacyEnforcementOutcome outcome
) {

    /** Validates that both privacy-safe event dimensions are present. */
    public PrivacyEnforcementEvent {
        Objects.requireNonNull(boundary, "boundary must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
