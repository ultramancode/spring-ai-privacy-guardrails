package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.List;
import java.util.Objects;

/**
 * Normalized outcomes in execution order, without inspected text.
 * Includes operational failures allowed by policy. Inspectors skipped after a block have no outcome.
 */
public record InspectionReport(InspectionDecision decision, List<Outcome> outcomes) {

    public record Outcome(String inspectorId, InspectionResult result) {
        public Outcome {
            ContentSegment.requireIdentifier(inspectorId);
            Objects.requireNonNull(result, "result");
        }
    }

    public InspectionReport {
        Objects.requireNonNull(decision, "decision");
        outcomes = List.copyOf(outcomes);
    }

    public boolean allowedAfterFailure() {
        return decision == InspectionDecision.ALLOW
                && outcomes.stream()
                        .anyMatch(o -> o.result().status() == InspectionResult.Status.FAILED);
    }
}
