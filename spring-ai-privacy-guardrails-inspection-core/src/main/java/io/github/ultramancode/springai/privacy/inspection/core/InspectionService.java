package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Executes inspectors in order and applies content and failure policies. */
public final class InspectionService {

    private record RegisteredInspector(
            String id, ContentInspector inspector, InspectionFailurePolicy failurePolicy) {}

    private final List<RegisteredInspector> inspectors;
    private final InspectionPolicy contentPolicy;

    public InspectionService(List<ContentInspector> inspectors) {
        this(inspectors, InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_CLOSED);
    }

    public InspectionService(
            List<ContentInspector> inspectors,
            InspectionPolicy contentPolicy,
            InspectionFailurePolicy defaultFailurePolicy) {
        this(inspectors, contentPolicy, defaultFailurePolicy, Map.of());
    }

    /**
     * Applies overrides by inspector ID, falling back to {@code defaultFailurePolicy}.
     * Override IDs must match configured inspectors. Failure policies are fixed at construction.
     */
    public InspectionService(
            List<ContentInspector> inspectors,
            InspectionPolicy contentPolicy,
            InspectionFailurePolicy defaultFailurePolicy,
            Map<String, InspectionFailurePolicy> failurePolicyOverrides) {
        this.contentPolicy = Objects.requireNonNull(contentPolicy, "contentPolicy");
        Objects.requireNonNull(defaultFailurePolicy, "defaultFailurePolicy");
        failurePolicyOverrides = Map.copyOf(failurePolicyOverrides);
        List<ContentInspector> configured = List.copyOf(inspectors);
        if (configured.isEmpty()) {
            throw new IllegalArgumentException("At least one inspector must be configured");
        }
        Set<String> ids = new HashSet<>();
        List<RegisteredInspector> registered = new ArrayList<>();
        for (ContentInspector inspector : configured) {
            String id = InspectionIdentifiers.requireValid(inspector.inspectorId());
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Inspector IDs must be distinct");
            }
            InspectionFailurePolicy inspectorFailurePolicy =
                    failurePolicyOverrides.getOrDefault(id, defaultFailurePolicy);
            registered.add(new RegisteredInspector(id, inspector, inspectorFailurePolicy));
        }
        if (!ids.containsAll(failurePolicyOverrides.keySet())) {
            throw new IllegalArgumentException("Failure policy overrides must reference configured inspector IDs");
        }
        this.inspectors = List.copyOf(registered);
    }

    /**
     * Checks privacy-processing requirements, then runs inspectors in configured order.
     * The request's time budget bounds further inspection work and response waits.
     * Expiry does not invalidate completed results or content policy decisions.
     * Results are validated before content policy evaluation. A block stops further inspectors.
     * Each inspector's {@link InspectionFailurePolicy} applies to eligible failures.
     * Allowed failures remain as failed outcomes in the report.
     *
     * @param request content and execution limits
     * @return final decision and inspector outcomes in execution order
     * @throws InspectionException on cancellation, exceeded limits, unmet privacy-processing
     *         requirements, configuration errors, unsupported content, invalid inspector results
     *         or content policy errors. The exception includes the outcomes collected so far
     */
    public InspectionReport inspect(InspectionRequest request) {
        Objects.requireNonNull(request, "request");
        List<InspectionReport.Outcome> outcomes = new ArrayList<>();
        try {
            InspectionRequest.checkInterrupted();
            for (RegisteredInspector registered : inspectors) {
                if (registered.inspector().requiresPrivacyProcessedContent()) {
                    request.requirePrivacyProcessed();
                }
            }
            Set<String> expectedSegmentIds = request.segments().stream()
                    .map(ContentSegment::id).collect(Collectors.toSet());
            for (RegisteredInspector registered : inspectors) {
                InspectionRequest.checkInterrupted();
                InspectionResult result = executeInspector(
                        registered.inspector(), request, expectedSegmentIds);
                outcomes.add(new InspectionReport.Outcome(registered.id(), result));
                InspectionRequest.checkInterrupted();
                if (cannotFailOpen(result.failureCode())) {
                    throw new InspectionException(result.failureCode());
                }
                InspectionDecision decision = Objects.requireNonNull(
                        contentPolicy.evaluate(registered.id(), result.findings()), "policy decision");
                InspectionRequest.checkInterrupted();
                if (decision == InspectionDecision.BLOCK
                        || (result.status() == InspectionResult.Status.FAILED
                                && registered.failurePolicy() == InspectionFailurePolicy.FAIL_CLOSED)) {
                    return new InspectionReport(InspectionDecision.BLOCK, outcomes);
                }
            }
            InspectionRequest.checkInterrupted();
            return new InspectionReport(InspectionDecision.ALLOW, outcomes);
        } catch (RuntimeException ex) {
            InspectionFailureCode failureCode = InspectionFailureCode.INVALID_RESULT;
            if (ex instanceof InspectionException failure) {
                failureCode = failure.failureCode();
            }
            if (Thread.currentThread().isInterrupted()) {
                failureCode = InspectionFailureCode.CANCELLED;
            }
            throw new InspectionException(failureCode, new InspectionReport(InspectionDecision.BLOCK, outcomes));
        }
    }

    private static InspectionResult executeInspector(
            ContentInspector inspector, InspectionRequest request, Set<String> expectedSegmentIds) {
        InspectionResult result;
        try {
            request.checkActive();
            result = inspector.inspect(request);
        } catch (InspectionException ex) {
            result = InspectionResult.failed(ex.failureCode());
        } catch (RuntimeException ex) {
            result = InspectionResult.failed(InspectionFailureCode.INVALID_RESULT);
        }
        return validateResult(result, expectedSegmentIds);
    }

    private static InspectionResult validateResult(InspectionResult result, Set<String> expectedSegmentIds) {
        if (result == null) {
            return InspectionResult.failed(InspectionFailureCode.INVALID_RESULT);
        }
        boolean hasUnknownCompletedSegmentIds = !expectedSegmentIds.containsAll(result.completedSegmentIds());
        boolean hasUnknownFindingSegmentIds = result.findings().stream()
                .anyMatch(finding -> !expectedSegmentIds.contains(finding.segmentId()));
        if (hasUnknownCompletedSegmentIds || hasUnknownFindingSegmentIds) {
            // Preserve an existing failure code that cannot fail open.
            InspectionFailureCode failureCode = InspectionFailureCode.INVALID_RESULT;
            if (cannotFailOpen(result.failureCode())) {
                failureCode = result.failureCode();
            }
            return InspectionResult.failed(failureCode);
        }
        if (result.status() == InspectionResult.Status.COMPLETED
                && !result.completedSegmentIds().equals(expectedSegmentIds)) {
            return InspectionResult.failed(
                    InspectionFailureCode.INVALID_RESULT, result.completedSegmentIds(), result.findings());
        }
        return result;
    }

    private static boolean cannotFailOpen(InspectionFailureCode failureCode) {
        if (failureCode == null) {
            return false;
        }
        return !failureCode.isFailOpenEligible();
    }
}
