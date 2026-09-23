package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Completion, coverage and evidence are independent; partial evidence must not disappear on failure.
 *
 * @param completedSegmentIds IDs of segments whose inspection finished; completion does not imply safety
 */
public record InspectionResult(
        Status status,
        Set<String> completedSegmentIds,
        List<InspectionFinding> findings,
        InspectionFailureCode failure) {

    public enum Status {
        COMPLETED,
        FAILED
    }

    public InspectionResult {
        Objects.requireNonNull(status, "status");
        completedSegmentIds = Set.copyOf(completedSegmentIds);
        findings = List.copyOf(findings);
        if (findings.size() > 10_000 || completedSegmentIds.size() > 10_000) {
            throw new IllegalArgumentException("Inspection result is too large");
        }
        completedSegmentIds.forEach(ContentSegment::requireIdentifier);
        if ((status == Status.FAILED) != (failure != null)) {
            throw new IllegalArgumentException("Only failed results carry a failure code");
        }
    }

    public static InspectionResult completed(Set<String> ids, List<InspectionFinding> findings) {
        return new InspectionResult(Status.COMPLETED, ids, findings, null);
    }

    public static InspectionResult failed(InspectionFailureCode failure) {
        return failed(failure, Set.of(), List.of());
    }

    public static InspectionResult failed(
            InspectionFailureCode failure, Set<String> ids, List<InspectionFinding> findings) {
        return new InspectionResult(Status.FAILED, ids, findings, Objects.requireNonNull(failure));
    }
}
