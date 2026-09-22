package io.github.ultramancode.springai.privacy.inspection.core;

/**
 * Evaluates inspection evidence to decide whether content may proceed.
 * Operational failure handling is configured separately through {@link InspectionFailurePolicy}.
 */
@FunctionalInterface
public interface InspectionPolicy {

    /** Evaluates one inspector's result, which may retain findings even when inspection failed. */
    InspectionDecision evaluate(InspectionResult result);

    /** Blocks any finding without applying an additional score threshold. */
    static InspectionPolicy blockFindings() {
        return result ->
                result.findings().isEmpty() ? InspectionDecision.ALLOW : InspectionDecision.BLOCK;
    }
}
