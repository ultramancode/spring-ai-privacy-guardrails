package io.github.ultramancode.springai.privacy.sample.inspection.infrastructure;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionBlockedException;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionExecution;
import io.github.ultramancode.springai.privacy.sample.inspection.model.InspectionRunResult;

import java.util.Objects;

// One recorder holds observations for one demo case.
public final class InspectionEvidenceRecorder {

    private String inspectedInput;
    private String inspectedOutput;
    private InspectionReport inputReport;
    private InspectionReport outputReport;
    private int modelCalls;
    private String modelInput;
    private String rawModelResponse;
    private InspectionExecution.Phase lastPhase;
    private InspectionExecution.Phase blockedAt;
    private String exceptionType;

    public void recordInspectedText(InspectionExecution.Phase phase, String inspectedText) {
        switch (phase) {
            case MODEL_REQUEST -> this.inspectedInput = inspectedText;
            case FINAL_OUTPUT -> this.inspectedOutput = inspectedText;
        }
    }

    public void recordInspection(InspectionReport report, InspectionExecution execution) {
        this.lastPhase = execution.phase();
        switch (execution.phase()) {
            case MODEL_REQUEST -> this.inputReport = report;
            case FINAL_OUTPUT -> this.outputReport = report;
        }
    }

    public void recordModelCall(String modelInput, String modelResponse) {
        this.modelCalls++;
        this.modelInput = modelInput;
        this.rawModelResponse = modelResponse;
    }

    public void recordBlock(InspectionBlockedException exception) {
        this.blockedAt = Objects.requireNonNull(this.lastPhase, "Blocked inspection phase was not recorded");
        this.exceptionType = exception.getClass().getSimpleName();
    }

    public InspectionRunResult createResult(String rawInput, String finalResponse) {
        return new InspectionRunResult(
                rawInput,
                this.inspectedInput,
                this.inputReport,
                this.modelCalls,
                this.modelInput,
                this.rawModelResponse,
                this.inspectedOutput,
                this.outputReport,
                finalResponse,
                this.blockedAt,
                this.exceptionType);
    }
}
