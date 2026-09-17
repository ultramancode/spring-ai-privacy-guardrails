package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Executes ordered, required inspectors. No cross-provider score averaging or silent skips. */
public final class InspectionService {

    private final List<ContentInspector> inspectors;
    private final InspectionPolicy policy;
    private final InspectionFailurePolicy failurePolicy;

    public InspectionService(List<ContentInspector> inspectors) {
        this(inspectors, InspectionPolicy.blockFindings(), InspectionFailurePolicy.BLOCK);
    }

    public InspectionService(
            List<ContentInspector> inspectors,
            InspectionPolicy policy,
            InspectionFailurePolicy failurePolicy) {
        this.inspectors = List.copyOf(inspectors);
        this.policy = Objects.requireNonNull(policy, "policy");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        if (this.inspectors.isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one required inspector must be configured");
        }
        Set<String> ids = new HashSet<>();
        for (ContentInspector inspector : this.inspectors) {
            if (!ids.add(ContentSegment.requireIdentifier(inspector.providerId()))) {
                throw new IllegalArgumentException("Inspector provider IDs must be distinct");
            }
        }
    }

    public InspectionReport inspect(InspectionRequest request) {
        Objects.requireNonNull(request, "request");
        InspectionRequest.checkInterrupted();
        // Preflight every provider before any provider can disclose content.
        for (ContentInspector inspector : inspectors) {
            if (inspector.requiresProtectedContent()) {
                request.requireProtected();
            }
        }
        Set<String> expectedSegmentIds =
                request.segments().stream().map(ContentSegment::id).collect(Collectors.toSet());
        List<InspectionReport.Outcome> outcomes = new ArrayList<>();
        for (ContentInspector inspector : inspectors) {
            InspectionResult inspectorResult = executeInspector(inspector, request, expectedSegmentIds);
            if (inspectorResult.failure() != null && isNonOverridable(inspectorResult.failure())) {
                throw new InspectionException(inspectorResult.failure());
            }
            // Preserve positive evidence even if the remainder timed out or was incomplete.
            InspectionDecision decision =
                    Objects.requireNonNull(policy.evaluate(inspectorResult), "policy decision");
            if (decision == InspectionDecision.BLOCK) {
                outcomes.add(new InspectionReport.Outcome(inspector.providerId(), inspectorResult));
                return new InspectionReport(InspectionDecision.BLOCK, outcomes);
            }
            InspectionResult completionCheckedResult =
                    verifyCompletion(request, inspectorResult, expectedSegmentIds);
            outcomes.add(new InspectionReport.Outcome(inspector.providerId(), completionCheckedResult));
            if (completionCheckedResult.status() == InspectionResult.Status.FAILED
                    && failurePolicy == InspectionFailurePolicy.BLOCK) {
                return new InspectionReport(InspectionDecision.BLOCK, outcomes);
            }
        }
        InspectionRequest.checkInterrupted();
        return new InspectionReport(InspectionDecision.ALLOW, outcomes);
    }

    private static InspectionResult executeInspector(
            ContentInspector inspector, InspectionRequest request, Set<String> expectedSegmentIds) {
        try {
            request.checkActive();
            InspectionResult result = inspector.inspect(request);
            InspectionRequest.checkInterrupted();
            if (result == null
                    || !expectedSegmentIds.containsAll(result.inspectedSegmentIds())
                    || result.findings().stream()
                            .anyMatch(f -> !expectedSegmentIds.contains(f.segmentId()))) {
                return InspectionResult.failed(InspectionFailure.INVALID_RESULT);
            }
            return result;
        } catch (InspectionException ex) {
            if (isNonOverridable(ex.failure())) {
                throw ex;
            }
            return InspectionResult.failed(ex.failure());
        } catch (RuntimeException ex) {
            InspectionRequest.checkInterrupted();
            return InspectionResult.failed(InspectionFailure.MODEL_ERROR);
        }
    }

    private static InspectionResult verifyCompletion(
            InspectionRequest request, InspectionResult result, Set<String> expectedSegmentIds) {
        if (result.status() != InspectionResult.Status.COMPLETED) {
            return result;
        }
        if (!result.inspectedSegmentIds().equals(expectedSegmentIds)) {
            return InspectionResult.failed(
                    InspectionFailure.INCOMPLETE, result.inspectedSegmentIds(), result.findings());
        }
        try {
            request.checkActive();
            return result;
        } catch (InspectionException ex) {
            if (isNonOverridable(ex.failure())) {
                throw ex;
            }
            return InspectionResult.failed(ex.failure(), result.inspectedSegmentIds(), result.findings());
        }
    }

    private static boolean isNonOverridable(InspectionFailure failure) {
        return failure == InspectionFailure.CANCELLED
                || failure == InspectionFailure.DISCLOSURE_DENIED
                || failure == InspectionFailure.CONFIGURATION
                || failure == InspectionFailure.UNSUPPORTED_CONTENT;
    }
}
