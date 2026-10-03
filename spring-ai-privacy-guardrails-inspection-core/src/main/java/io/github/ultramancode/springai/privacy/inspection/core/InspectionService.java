package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Executes inspectors in order and applies content and failure policies. */
public final class InspectionService {

    private record RegisteredInspector(String id, ContentInspector inspector) {}

    private final List<RegisteredInspector> inspectors;
    private final InspectionPolicy policy;
    private final InspectionFailurePolicy failurePolicy;

    public InspectionService(List<ContentInspector> inspectors) {
        this(inspectors, InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_CLOSED);
    }

    public InspectionService(
            List<ContentInspector> inspectors,
            InspectionPolicy policy,
            InspectionFailurePolicy failurePolicy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
        List<ContentInspector> configured = List.copyOf(inspectors);
        if (configured.isEmpty()) {
            throw new IllegalArgumentException("At least one inspector must be configured");
        }
        Set<String> ids = new HashSet<>();
        List<RegisteredInspector> registered = new ArrayList<>();
        for (ContentInspector inspector : configured) {
            String id = ContentSegment.requireIdentifier(inspector.inspectorId());
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Inspector IDs must be distinct");
            }
            registered.add(new RegisteredInspector(id, inspector));
        }
        this.inspectors = List.copyOf(registered);
    }

    /**
     * Preflights privacy-processing requirements, then executes inspectors in order
     * with one shared deadline. Results are validated before their findings reach the
     * content policy. A content block short-circuits. FAIL_OPEN only allows operational
     * failures and retains their failed outcomes. It cannot override a content block.
     *
     * @param request content snapshot and execution limits
     * @return normalized outcomes processed up to the decision
     * @throws InspectionException on cancellation, limits, denied disclosure, configuration
     *         errors, unsupported content or invalid inspector results, with collected outcomes
     *         and no original cause. Invalid policy decisions also block this way
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
            Set<String> expectedIds = request.segments().stream()
                    .map(ContentSegment::id).collect(Collectors.toSet());
            int remainingFindings = request.limits().maxFindings();
            for (RegisteredInspector registered : inspectors) {
                InspectionResult result = executeInspector(
                        registered.inspector(), request, expectedIds, remainingFindings);
                outcomes.add(new InspectionReport.Outcome(registered.id(), result));
                remainingFindings -= result.findings().size();
                if (isNonOverridable(result.failure())) {
                    throw new InspectionException(result.failure());
                }
                InspectionDecision decision = Objects.requireNonNull(
                        policy.evaluate(registered.id(), result.findings()), "policy decision");
                InspectionResult checkedResult = resolveCompletion(request, result);
                if (checkedResult != result) {
                    result = checkedResult;
                    outcomes.set(outcomes.size() - 1, new InspectionReport.Outcome(registered.id(), result));
                }
                if (isNonOverridable(result.failure())) {
                    throw new InspectionException(result.failure());
                }
                if (decision == InspectionDecision.BLOCK
                        || (result.status() == InspectionResult.Status.FAILED
                                && failurePolicy == InspectionFailurePolicy.FAIL_CLOSED)) {
                    return new InspectionReport(InspectionDecision.BLOCK, outcomes);
                }
            }
            InspectionRequest.checkInterrupted();
            return new InspectionReport(InspectionDecision.ALLOW, outcomes);
        } catch (InspectionException ex) {
            throw new InspectionException(ex.failure(), new InspectionReport(InspectionDecision.BLOCK, outcomes));
        } catch (RuntimeException ex) {
            InspectionFailureCode failure = request.resolveFailure(InspectionFailureCode.INVALID_RESULT);
            throw new InspectionException(failure, new InspectionReport(InspectionDecision.BLOCK, outcomes));
        }
    }

    private static InspectionResult executeInspector(
            ContentInspector inspector, InspectionRequest request, Set<String> expectedIds,
            int remainingFindings) {
        InspectionResult result;
        try {
            request.checkActive();
            result = inspector.inspect(request);
        } catch (InspectionException ex) {
            result = InspectionResult.failed(ex.failure());
        } catch (RuntimeException ex) {
            result = InspectionResult.failed(InspectionFailureCode.INVALID_RESULT);
        }
        result = validateResult(result, expectedIds, remainingFindings);
        return resolveCompletion(request, result);
    }

    private static InspectionResult resolveCompletion(InspectionRequest request, InspectionResult result) {
        InspectionFailureCode failure;
        if (result.status() == InspectionResult.Status.COMPLETED) {
            try {
                request.checkActive();
                return result;
            } catch (InspectionException ex) {
                failure = ex.failure();
            }
        } else {
            failure = request.resolveFailure(result.failure());
            if (failure == result.failure()) {
                return result;
            }
        }
        return InspectionResult.failed(failure, result.completedSegmentIds(), result.findings());
    }

    private static InspectionResult validateResult(
            InspectionResult result, Set<String> expectedIds, int remainingFindings) {
        if (result == null) {
            return InspectionResult.failed(InspectionFailureCode.INVALID_RESULT);
        }
        if (result.findings().size() > remainingFindings) {
            InspectionFailureCode failure = InspectionFailureCode.LIMIT_EXCEEDED;
            if (isNonOverridable(result.failure())) {
                failure = result.failure();
            }
            result = InspectionResult.failed(failure, result.completedSegmentIds(),
                    result.findings().subList(0, remainingFindings));
        }
        if (!expectedIds.containsAll(result.completedSegmentIds())
                || result.findings().stream().anyMatch(f -> !expectedIds.contains(f.segmentId()))) {
            // Invalid associations must not downgrade a declared hard failure to an operational one.
            return InspectionResult.failed(isNonOverridable(result.failure())
                    ? result.failure() : InspectionFailureCode.INVALID_RESULT);
        }
        if (result.status() == InspectionResult.Status.COMPLETED
                && !result.completedSegmentIds().equals(expectedIds)) {
            return InspectionResult.failed(
                    InspectionFailureCode.INVALID_RESULT, result.completedSegmentIds(), result.findings());
        }
        return result;
    }

    private static boolean isNonOverridable(InspectionFailureCode failure) {
        return failure != null && !failure.isFailOpenEligible();
    }
}
