package io.github.ultramancode.springai.privacy.inspection.core;

/** Content decisions are independent of execution failures and provider scores. */
@FunctionalInterface
public interface InspectionPolicy {

    InspectionDecision evaluate(InspectionResult result);

    static InspectionPolicy blockFindings() {
        return result ->
                result.findings().isEmpty() ? InspectionDecision.ALLOW : InspectionDecision.BLOCK;
    }
}
