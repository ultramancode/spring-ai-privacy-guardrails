package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.List;

/**
 * Evaluates findings to decide whether a request may proceed.
 * Inspector failures are handled separately through {@link InspectionFailurePolicy}.
 * Implementations must be thread-safe. The same instance may be invoked concurrently.
 */
@FunctionalInterface
public interface InspectionPolicy {

    /**
     * Evaluates findings from one inspector, including findings collected before a failure.
     * The supplied list is immutable. Configure detection thresholds in the inspector,
     * since scores depend on the model that produced them.
     *
     * <p>{@link InspectionService} handles completion status and failures separately.
     * Evaluation time counts toward the request's timeout, so implementations should
     * return promptly. A null decision or an unclassified runtime exception causes
     * {@link InspectionFailureCode#INVALID_RESULT}, even with FAIL_OPEN.
     */
    InspectionDecision evaluate(String inspectorId, List<InspectionFinding> findings);

    /** Blocks any finding without applying an additional score threshold. */
    static InspectionPolicy blockFindings() {
        return (inspectorId, findings) ->
                findings.isEmpty() ? InspectionDecision.ALLOW : InspectionDecision.BLOCK;
    }
}
