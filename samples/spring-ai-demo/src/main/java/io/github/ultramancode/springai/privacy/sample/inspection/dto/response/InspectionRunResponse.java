package io.github.ultramancode.springai.privacy.sample.inspection.dto.response;

import io.github.ultramancode.springai.privacy.inspection.springai.InspectionExecution;
import io.github.ultramancode.springai.privacy.sample.inspection.model.InspectionRunResult;

public record InspectionRunResponse(
        String rawInput,
        InspectionEvidenceResponse inputInspection,
        int modelCalls,
        String modelInput,
        String rawModelResponse,
        InspectionEvidenceResponse outputInspection,
        String finalResponse,
        InspectionExecution.Phase blockedAt,
        String exceptionType
) {

    public static InspectionRunResponse from(InspectionRunResult result) {
        InspectionEvidenceResponse inputInspection = null;
        if (result.inputReport() != null) {
            inputInspection = new InspectionEvidenceResponse(result.inspectedInput(), result.inputReport());
        }
        InspectionEvidenceResponse outputInspection = null;
        if (result.outputReport() != null) {
            outputInspection = new InspectionEvidenceResponse(result.inspectedOutput(), result.outputReport());
        }
        return new InspectionRunResponse(
                result.rawInput(),
                inputInspection,
                result.modelCalls(),
                result.modelInput(),
                result.rawModelResponse(),
                outputInspection,
                result.finalResponse(),
                result.blockedAt(),
                result.exceptionType());
    }
}
