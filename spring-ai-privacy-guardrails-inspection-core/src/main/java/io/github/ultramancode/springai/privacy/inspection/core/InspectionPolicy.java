package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.List;

/**
 * Evaluates inspection evidence to decide whether content may proceed.
 * Operational failure handling is configured separately through {@link InspectionFailurePolicy}.
 */
@FunctionalInterface
public interface InspectionPolicy {

    /**
     * Evaluates immutable findings from one configured inspector, including valid partial
     * evidence collected before an operational failure. Scores are inspector-local.
     * Model-specific detection thresholds belong to the inspector. Completion and
     * failure handling belong to {@link InspectionService}, not this policy.
     * Evaluation consumes the shared inspection deadline, so implementations should return promptly.
     * A null decision or an unclassified runtime exception is a hard INVALID_RESULT failure.
     */
    InspectionDecision evaluate(String inspectorId, List<InspectionFinding> findings);

    /** Blocks any finding without applying an additional score threshold. */
    static InspectionPolicy blockFindings() {
        return (inspectorId, findings) ->
                findings.isEmpty() ? InspectionDecision.ALLOW : InspectionDecision.BLOCK;
    }
}
