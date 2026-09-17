package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Completion, coverage and evidence are independent; partial evidence must not disappear on failure. */
public record InspectionResult(
        Status status,
        Set<String> inspectedSegmentIds,
        List<InspectionFinding> findings,
        InspectionFailure failure) {

    public enum Status {
        COMPLETED,
        FAILED
    }

    public InspectionResult {
        Objects.requireNonNull(status, "status");
        inspectedSegmentIds = Set.copyOf(inspectedSegmentIds);
        findings = List.copyOf(findings);
        if (findings.size() > 10_000 || inspectedSegmentIds.size() > 10_000) {
            throw new IllegalArgumentException("Inspection result is too large");
        }
        inspectedSegmentIds.forEach(ContentSegment::requireIdentifier);
        if ((status == Status.FAILED) != (failure != null)) {
            throw new IllegalArgumentException("Only failed results carry a failure code");
        }
    }

    public static InspectionResult completed(Set<String> ids, List<InspectionFinding> findings) {
        return new InspectionResult(Status.COMPLETED, ids, findings, null);
    }

    public static InspectionResult failed(InspectionFailure failure) {
        return failed(failure, Set.of(), List.of());
    }

    public static InspectionResult failed(
            InspectionFailure failure, Set<String> ids, List<InspectionFinding> findings) {
        return new InspectionResult(Status.FAILED, ids, findings, Objects.requireNonNull(failure));
    }
}
