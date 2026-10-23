package io.github.ultramancode.springai.privacy.sample.inspection.dto.response;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionDecision;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.sample.inspection.model.InspectionDemoCaseType;
import io.github.ultramancode.springai.privacy.sample.inspection.model.InspectionRunResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionDemoResponseTest {

    @Test
    void representsSkippedInspectionAsAbsentForEachDirection() {
        InspectionReport allowed = new InspectionReport(InspectionDecision.ALLOW, List.of());
        InspectionRunResult inputResult = new InspectionRunResult(
                "request", "request", allowed, 1, "request", "response",
                null, null, "response", null, null);
        InspectionRunResult outputResult = new InspectionRunResult(
                "request", null, null, 1, "request", "response",
                "response", allowed, "response", null, null);

        InspectionDemoResponse response = InspectionDemoResponse.from(
                InspectionDemoCaseType.ALLOW, inputResult, outputResult, 0);

        assertThat(response.inputCase().inputInspection().report()).isEqualTo(allowed);
        assertThat(response.inputCase().outputInspection()).isNull();
        assertThat(response.outputCase().inputInspection()).isNull();
        assertThat(response.outputCase().outputInspection().report()).isEqualTo(allowed);
    }
}
