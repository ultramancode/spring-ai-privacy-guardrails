package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.List;
import java.util.Objects;

/**
 * Payload-free, normalized outcomes in execution order, including failed-but-explicitly-allowed
 * outcomes. Short-circuiting leaves unexecuted inspectors out of the list.
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
