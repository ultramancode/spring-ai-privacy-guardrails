package io.github.ultramancode.springai.privacy.sample.inspection.dto.response;

import io.github.ultramancode.springai.privacy.sample.inspection.model.InspectionDemoCaseType;
import io.github.ultramancode.springai.privacy.sample.inspection.model.InspectionRunResult;

import java.util.Locale;

public record InspectionDemoResponse(
        String example,
        InspectionRunResponse inputCase,
        InspectionRunResponse outputCase,
        int activeSessionsAfterCall
) {

    public static InspectionDemoResponse from(
            InspectionDemoCaseType caseType,
            InspectionRunResult inputResult,
            InspectionRunResult outputResult,
            int activeSessionsAfterCall
    ) {
        return new InspectionDemoResponse(
                caseType.name().toLowerCase(Locale.ROOT),
                InspectionRunResponse.from(inputResult),
                InspectionRunResponse.from(outputResult),
                activeSessionsAfterCall);
    }
}
