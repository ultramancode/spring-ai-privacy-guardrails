package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.List;

/**
 * Evaluates inspection evidence to decide whether content may proceed.
 * Operational failure handling is configured separately through {@link InspectionFailurePolicy}.
 * Implementations must be thread-safe. The same instance may be invoked concurrently.
 */
@FunctionalInterface
public interface InspectionPolicy {

    /**
     * Evaluates immutable findings from one configured inspector, including valid partial
     * evidence collected before an operational failure. Scores are inspector-local.
     * Model-specific detection thresholds belong to the inspector. Completion and
     * failure handling belong to {@link InspectionService}, not this policy.
     * Evaluation time reduces the budget available to later inspectors, so implementations
     * should return promptly.
     * A null decision or an unclassified runtime exception becomes
     * {@link InspectionFailureCode#INVALID_RESULT}, which cannot fail open.
     */
    InspectionDecision evaluate(String inspectorId, List<InspectionFinding> findings);

    /** Blocks any finding without applying an additional score threshold. */
    static InspectionPolicy blockFindings() {
        return (inspectorId, findings) ->
                findings.isEmpty() ? InspectionDecision.ALLOW : InspectionDecision.BLOCK;
    }
}
