package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One inspector's completion status, completed segment IDs and findings.
 * Failed results must retain findings collected before failure.
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
        COMPLETED,
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
