package io.github.ultramancode.springai.privacy.sample.inspection.model;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionExecution;

public record InspectionRunResult(
        String rawInput,
        String inspectedInput,
        InspectionReport inputReport,
        int modelCalls,
        String modelInput,
        String rawModelResponse,
        String inspectedOutput,
        InspectionReport outputReport,
        String finalResponse,
        InspectionExecution.Phase blockedAt,
        String exceptionType
) {
}
