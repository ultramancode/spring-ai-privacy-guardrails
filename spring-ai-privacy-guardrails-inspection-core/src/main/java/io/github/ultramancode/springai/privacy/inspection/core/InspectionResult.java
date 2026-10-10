package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The completion status and findings from one inspector.
 * A completed result must identify every request segment. If inspection fails, retain
 * the IDs of fully inspected segments and all findings collected before the failure.
 *
 * @param completedSegmentIds IDs of segments whose inspection finished. Completion does not imply safety
 * @param failureCode required for {@link Status#FAILED}, must be {@code null} for {@link Status#COMPLETED}
 */
public record InspectionResult(
        Status status,
        Set<String> completedSegmentIds,
        List<InspectionFinding> findings,
        InspectionFailureCode failureCode) {

    public enum Status {
        /** All request segments were inspected. Findings may still cause the policy to block the request. */
        COMPLETED,
        /** Inspection could not complete, or a requirement prevented it from starting. */
        FAILED
    }

    public InspectionResult {
        Objects.requireNonNull(status, "status");
        completedSegmentIds = Set.copyOf(completedSegmentIds);
        findings = List.copyOf(findings);
        completedSegmentIds.forEach(InspectionIdentifiers::requireValid);
        if (status == Status.FAILED && failureCode == null) {
            throw new IllegalArgumentException("Failed results require a failure code");
        }
        if (status == Status.COMPLETED && failureCode != null) {
            throw new IllegalArgumentException("Completed results cannot carry a failure code");
        }
    }

    public static InspectionResult completed(Set<String> ids, List<InspectionFinding> findings) {
        return new InspectionResult(Status.COMPLETED, ids, findings, null);
    }

    public static InspectionResult failed(InspectionFailureCode failureCode) {
        return failed(failureCode, Set.of(), List.of());
    }

    public static InspectionResult failed(
            InspectionFailureCode failureCode, Set<String> ids, List<InspectionFinding> findings) {
        return new InspectionResult(Status.FAILED, ids, findings, Objects.requireNonNull(failureCode));
    }
}
