package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionReportTest {

    @Test
    void uninvokedInspectorsCannotReportCompletedWorkOrFindings() {
        InspectionFinding finding = new InspectionFinding(
                "s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        List<InspectionResult> invalidResults = List.of(
                InspectionResult.completed(Set.of(), List.of()),
                InspectionResult.failed(InspectionFailureCode.TIMEOUT, Set.of("s1"), List.of()),
                InspectionResult.failed(InspectionFailureCode.TIMEOUT, Set.of(), List.of(finding)));

        for (InspectionResult result : invalidResults) {
            assertThatThrownBy(() -> new InspectionReport.Outcome("guard", false, result))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
