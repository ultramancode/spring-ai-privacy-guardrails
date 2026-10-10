package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.List;
import java.util.Objects;

/**
 * The final decision and individual inspector results, without inspected text.
 * Results follow the configured inspector order and include failures allowed by policy.
 *
 * <p>A result can describe a failure detected before the inspector was called.
 * If a privacy requirement prevents inspection, only the first inspector whose requirement
 * could not be met is recorded. Other inspectors skipped because of a block have no result.
 */
public record InspectionReport(InspectionDecision decision, List<Outcome> outcomes) {

    /**
     * One inspector's outcome, including whether its {@link ContentInspector#inspect(InspectionRequest)}
     * method was called. Invocation does not imply that an external request was sent.
     * If the inspector was not invoked, the result must be failed with no completed segments or findings.
     *
     * @param inspectorId configured inspector identifier
     * @param inspectorInvoked whether the service called the inspector, including calls that threw an exception
     * @param result validated inspection result or the failure that prevented invocation
     */
    public record Outcome(String inspectorId, boolean inspectorInvoked, InspectionResult result) {
        public Outcome {
            InspectionIdentifiers.requireValid(inspectorId);
            Objects.requireNonNull(result, "result");
            if (!inspectorInvoked && (result.status() != InspectionResult.Status.FAILED
                    || !result.completedSegmentIds().isEmpty() || !result.findings().isEmpty())) {
                throw new IllegalArgumentException(
                        "An inspector that was not invoked cannot report completed work or findings");
            }
        }
    }

    public InspectionReport {
        Objects.requireNonNull(decision, "decision");
        outcomes = List.copyOf(outcomes);
    }

    /** Returns whether the request was allowed despite at least one failed inspector result. */
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
