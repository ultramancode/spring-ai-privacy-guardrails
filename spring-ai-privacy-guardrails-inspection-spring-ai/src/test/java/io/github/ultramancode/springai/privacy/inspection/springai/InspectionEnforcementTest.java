package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionDecision;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionEnforcementTest {

    @ParameterizedTest
    @EnumSource(InspectionResult.Status.class)
    void interruptionDuringObserverPreservesResultsAndNotifiesFailure(InspectionResult.Status status) {
        ContentSegment segment = new ContentSegment("segment", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.UNKNOWN, "synthetic-private-text");
        List<InspectionFinding> findings = List.of(new InspectionFinding(
                segment.id(), InspectionFinding.Category.PROMPT_INJECTION, "test.detected", 0.9));
        InspectionResult inspectorResult;
        if (status == InspectionResult.Status.COMPLETED) {
            inspectorResult = InspectionResult.completed(Set.of(segment.id()), findings);
        } else {
            inspectorResult = InspectionResult.failed(
                    InspectionFailureCode.MODEL_ERROR, Set.of(segment.id()), findings);
        }
        ContentInspector inspector = new ContentInspector() {
            @Override
            public InspectionResult inspect(InspectionRequest request) {
                return inspectorResult;
            }

            @Override
            public String inspectorId() {
                return "test";
            }

            @Override
            public boolean requiresPrivacyProcessedContent() {
                return false;
            }
        };
        List<InspectionReport> observedReports = new ArrayList<>();
        List<InspectionException> observedFailures = new ArrayList<>();
        InspectionObserver observer = new InspectionObserver() {
            @Override
            public void onInspection(InspectionReport report) {
                observedReports.add(report);
                Thread.currentThread().interrupt();
            }

            @Override
            public void onFailure(InspectionException failure) {
                observedFailures.add(failure);
            }
        };
        InspectionService service = new InspectionService(List.of(inspector),
                (inspectorId, inspectedFindings) -> InspectionDecision.ALLOW, InspectionFailurePolicy.FAIL_OPEN);
        InspectionEnforcement enforcement = new InspectionEnforcement(service, InspectionLimits.defaults(), observer);
        List<InspectionReport.Outcome> expectedOutcomes =
                List.of(new InspectionReport.Outcome(inspector.inspectorId(), true, inspectorResult));

        try {
            assertThatThrownBy(() -> enforcement.inspect(() -> List.of(segment)))
                    .isInstanceOfSatisfying(InspectionException.class, failure -> {
                        assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);
                        assertThat(failure).hasNoCause();
                        assertThat(failure.report()).contains(
                                new InspectionReport(InspectionDecision.BLOCK, expectedOutcomes));
                        assertThat(observedFailures).containsExactly(failure);
                    });
            assertThat(observedReports).containsExactly(
                    new InspectionReport(InspectionDecision.ALLOW, expectedOutcomes));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
