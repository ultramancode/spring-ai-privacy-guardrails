package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.List;
import java.util.Objects;

/** Payload-free aggregate, including failed-but-explicitly-allowed outcomes. */
public record InspectionReport(InspectionDecision decision, List<Outcome> outcomes) {

    public record Outcome(String providerId, InspectionResult result) {
        public Outcome {
            ContentSegment.requireIdentifier(providerId);
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
