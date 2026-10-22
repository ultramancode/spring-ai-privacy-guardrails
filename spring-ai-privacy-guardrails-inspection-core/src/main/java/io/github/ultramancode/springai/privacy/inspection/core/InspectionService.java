package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** Runs inspectors in the supplied order and decides whether the request may proceed. */
public final class InspectionService {

    private record RegisteredInspector(
            String id, ContentInspector inspector, InspectionFailurePolicy failurePolicy) {}

    private final List<RegisteredInspector> inspectors;
    private final InspectionPolicy contentPolicy;
    private final Set<String> piiTokenSimplificationInspectorIds;

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
     * Creates a service with a content policy and failure policies for its inspectors.
     * The override map uses inspector IDs as keys. IDs must match configured inspectors.
     * Inspectors without an override use {@code defaultFailurePolicy}.
     * Later changes to the supplied collections do not change this service's configuration.
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
        List<RegisteredInspector> registeredInspectors = new ArrayList<>();
        for (ContentInspector inspector : configured) {
            String id = InspectionIdentifiers.requireValid(inspector.inspectorId());
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Inspector IDs must be distinct");
            }
            InspectionFailurePolicy inspectorFailurePolicy =
                    failurePolicyOverrides.getOrDefault(id, defaultFailurePolicy);
            registeredInspectors.add(new RegisteredInspector(id, inspector, inspectorFailurePolicy));
        }
        if (!ids.containsAll(failurePolicyOverrides.keySet())) {
            throw new IllegalArgumentException("Failure policy overrides must reference configured inspector IDs");
        }
        this.inspectors = List.copyOf(registeredInspectors);
        this.piiTokenSimplificationInspectorIds = Set.of();
    }

    /**
     * Creates the new service returned by {@link #withPiiTokenSimplification(Set)}.
     * Reuses the existing registered inspectors and content policy.
     * Each registered inspector already contains its final failure policy after applying
     * the default failure policy and any inspector-specific override.
     * The supplied inspector IDs replace the PII token simplification selection in the new service.
     * The existing service remains unchanged.
     */
    private InspectionService(List<RegisteredInspector> inspectors, InspectionPolicy contentPolicy,
            Set<String> piiTokenSimplificationInspectorIds) {
        this.inspectors = inspectors;
        this.contentPolicy = contentPolicy;
        this.piiTokenSimplificationInspectorIds = piiTokenSimplificationInspectorIds;
    }

    /**
     * Returns a new service that simplifies PII tokens in copies of the inspection request
     * sent to the selected inspectors. The original request remains unchanged.
     * The supplied inspector IDs replace the previous selection.
     * Other inspectors receive the original request text. No inspectors are selected by default.
     * The Spring AI privacy integration supplies the simplifier. Direct callers supply it
     * through {@link #inspect(InspectionRequest, UnaryOperator)}.
     *
     * @param inspectorIds configured inspector IDs that should receive simplified PII tokens
     * @return a service using the supplied selection and the same inspectors and policies
     * @throws IllegalArgumentException if an ID does not match a configured inspector
     */
    public InspectionService withPiiTokenSimplification(Set<String> inspectorIds) {
        Set<String> selectedInspectorIds = Set.copyOf(inspectorIds);
        Set<String> configuredInspectorIds = inspectors.stream().map(RegisteredInspector::id).collect(Collectors.toSet());
        if (!configuredInspectorIds.containsAll(selectedInspectorIds)) {
            throw new IllegalArgumentException("PII token simplification must reference configured inspector IDs");
        }
        return new InspectionService(inspectors, contentPolicy, selectedInspectorIds);
    }

    /** Whether any configured inspector requires a session-bound PII token simplifier. */
    public boolean requiresPiiTokenSimplifier() {
        return !piiTokenSimplificationInspectorIds.isEmpty();
    }

    /**
     * Checks the input's privacy processing status, then runs the configured inspectors.
     * If the input fails a privacy requirement, no inspector runs. The exception report
     * identifies the first inspector whose requirement could not be met.
     *
     * <p>Each result is validated before its findings are evaluated by the content policy.
     * A decision to block stops subsequent inspectors. Eligible failures are handled by
     * each inspector's {@link InspectionFailurePolicy} and remain in the report even if allowed.
     *
     * <p>The request's timeout limits further work and response waits. Results and policy
     * decisions already produced remain valid after the timeout expires.
     *
     * @param inspectionRequest content and execution limits
     * @return final decision and inspector outcomes in configured order
     * @throws InspectionException on cancellation, exceeded limits, unmet privacy-processing
     *         requirements, configuration errors, closed inspectors, unsupported content,
     *         invalid inspector results or content policy errors. The exception includes
     *         the results collected so far
     */
    public InspectionReport inspect(InspectionRequest inspectionRequest) {
        return inspect(inspectionRequest, null);
    }

    /**
     * Inspects content with an optional session-bound PII token simplifier.
     * The simplifier must replace only tokens issued by that session and preserve other text.
     * Transformed copies retain segment metadata and share the original request's time budget.
     *
     * @param inspectionRequest content and execution limits
     * @param piiTokenSimplifier token simplifier, required when inspectors are selected for PII token simplification
     * @return the inspection report
     * @throws InspectionException if inspection fails or the required simplifier is unavailable
     */
    public InspectionReport inspect(
            InspectionRequest inspectionRequest, UnaryOperator<String> piiTokenSimplifier) {
        Objects.requireNonNull(inspectionRequest, "inspectionRequest");
        List<InspectionReport.Outcome> outcomes = new ArrayList<>();
        try {
            InspectionRequest.checkInterrupted();
            if (requiresPiiTokenSimplifier() && piiTokenSimplifier == null) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            for (RegisteredInspector registeredInspector : inspectors) {
                if (registeredInspector.inspector().requiresPrivacyProcessedContent()) {
                    try {
                        inspectionRequest.requirePrivacyProcessed();
                    } catch (InspectionException ex) {
                        InspectionResult result = InspectionResult.failed(ex.code());
                        outcomes.add(new InspectionReport.Outcome(registeredInspector.id(), false, result));
                        throw ex;
                    }
                }
            }
            Set<String> expectedSegmentIds = inspectionRequest.segments().stream()
                    .map(ContentSegment::id).collect(Collectors.toSet());
            for (RegisteredInspector registeredInspector : inspectors) {
                InspectionRequest.checkInterrupted();
                UnaryOperator<String> inspectorPiiTokenSimplifier = null;
                if (piiTokenSimplificationInspectorIds.contains(registeredInspector.id())) {
                    inspectorPiiTokenSimplifier = piiTokenSimplifier;
                }
                InspectionReport.Outcome outcome = executeInspector(
                        registeredInspector, inspectionRequest, expectedSegmentIds, inspectorPiiTokenSimplifier);
                outcomes.add(outcome);
                InspectionResult result = outcome.result();
                InspectionRequest.checkInterrupted();
                if (cannotFailOpen(result.failureCode())) {
                    throw new InspectionException(result.failureCode());
                }
                InspectionDecision decision = Objects.requireNonNull(
                        contentPolicy.evaluate(registeredInspector.id(), result.findings()), "policy decision");
                InspectionRequest.checkInterrupted();
                if (decision == InspectionDecision.BLOCK
                        || (result.status() == InspectionResult.Status.FAILED
                                && registeredInspector.failurePolicy() == InspectionFailurePolicy.FAIL_CLOSED)) {
                    return new InspectionReport(InspectionDecision.BLOCK, outcomes);
                }
            }
            InspectionRequest.checkInterrupted();
            return new InspectionReport(InspectionDecision.ALLOW, outcomes);
        } catch (RuntimeException ex) {
            InspectionFailureCode failureCode = InspectionFailureCode.INVALID_RESULT;
            if (ex instanceof InspectionException failure) {
                failureCode = failure.code();
            }
            if (Thread.currentThread().isInterrupted()) {
                failureCode = InspectionFailureCode.CANCELLED;
            }
            throw new InspectionException(failureCode, new InspectionReport(InspectionDecision.BLOCK, outcomes));
        }
    }

    private static InspectionReport.Outcome executeInspector(
            RegisteredInspector registeredInspector,
            InspectionRequest inspectionRequest,
            Set<String> expectedSegmentIds,
            UnaryOperator<String> piiTokenSimplifier) {
        boolean inspectorInvoked = false;
        InspectionResult result;
        try {
            inspectionRequest.checkActive();
            if (piiTokenSimplifier != null) {
                inspectionRequest = simplifyPiiTokens(inspectionRequest, piiTokenSimplifier);
            }
            inspectorInvoked = true;
            result = registeredInspector.inspector().inspect(inspectionRequest);
        } catch (InspectionException ex) {
            result = InspectionResult.failed(ex.code());
        } catch (RuntimeException ex) {
            result = InspectionResult.failed(InspectionFailureCode.INVALID_RESULT);
        }
        InspectionResult validatedResult = validateResult(result, expectedSegmentIds);
        return new InspectionReport.Outcome(registeredInspector.id(), inspectorInvoked, validatedResult);
    }

    private static InspectionRequest simplifyPiiTokens(
            InspectionRequest inspectionRequest, UnaryOperator<String> piiTokenSimplifier) {
        try {
            return inspectionRequest.withSimplifiedPiiTokens(piiTokenSimplifier);
        } catch (InspectionException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
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
