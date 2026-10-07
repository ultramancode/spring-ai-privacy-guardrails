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
            InspectionIdentifiers.requireValid(inspectorId);
            Objects.requireNonNull(result, "result");
        }
    }

    public InspectionReport {
        Objects.requireNonNull(decision, "decision");
        outcomes = List.copyOf(outcomes);
    }

    public boolean allowedAfterFailure() {
        if (decision != InspectionDecision.ALLOW) {
            return false;
        }

        for (Outcome outcome : outcomes) {
            if (outcome.result().status() == InspectionResult.Status.FAILED) {
                return true;
            }
        }
        return false;
    }
}
