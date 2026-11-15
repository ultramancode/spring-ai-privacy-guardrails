package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectorExecutionListenerTest {

    private record Execution(String inspectorId, InspectionReport.Outcome outcome, InspectionDecision decision) {}

    private final List<String> started = new ArrayList<>();
    private final List<Execution> finished = new ArrayList<>();

    private final InspectorExecutionListener recordingListener = inspectorId -> {
        started.add(inspectorId);
        return (outcome, decision) -> finished.add(new Execution(inspectorId, outcome, decision));
    };

    private InspectionRequest request(ContentSegment.PrivacyProcessingStatus privacyProcessingStatus) {
        return new InspectionRequest(List.of(new ContentSegment("s1", ContentSegment.Role.USER,
                privacyProcessingStatus, "private raw text")), InspectionLimits.defaults());
    }

    private ContentInspector inspector(String inspectorId, InspectionResult result) {
        return inspector(inspectorId, result, false);
    }

    private ContentInspector inspector(String inspectorId, InspectionResult result,
            boolean requiresPrivacyProcessedContent) {
        return new ContentInspector() {
            public String inspectorId() {
                return inspectorId;
            }

            public boolean requiresPrivacyProcessedContent() {
                return requiresPrivacyProcessedContent;
            }

            public InspectionResult inspect(InspectionRequest request) {
                return result;
            }
        };
    }

    @Test
    void reportsEachExecutedInspectorWithItsOutcomeAndTheServiceDecision() {
        InspectionFinding finding = new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", 0.9);
        InspectionService service = new InspectionService(List.of(
                inspector("timeout", InspectionResult.failed(InspectionFailureCode.TIMEOUT)),
                inspector("attack", InspectionResult.completed(Set.of("s1"), List.of(finding))),
                inspector("skipped", InspectionResult.completed(Set.of("s1"), List.of()))),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        InspectionReport report = service.inspect(
                request(ContentSegment.PrivacyProcessingStatus.UNKNOWN), null, recordingListener);

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(started).containsExactly("timeout", "attack");
        assertThat(finished).containsExactly(
                new Execution("timeout", report.outcomes().get(0), InspectionDecision.ALLOW),
                new Execution("attack", report.outcomes().get(1), InspectionDecision.BLOCK));
    }

    @Test
    void reportsFailClosedFailureAsBlock() {
        InspectionService service = new InspectionService(
                List.of(inspector("timeout", InspectionResult.failed(InspectionFailureCode.TIMEOUT))));

        InspectionReport report = service.inspect(
                request(ContentSegment.PrivacyProcessingStatus.UNKNOWN), null, recordingListener);

        assertThat(finished).containsExactly(
                new Execution("timeout", report.outcomes().get(0), InspectionDecision.BLOCK));
    }

    @Test
    void reportsValidatedOutcomeWithoutDecisionWhenTheServiceStops() {
        InspectionService service = new InspectionService(List.of(
                inspector("invalid", InspectionResult.completed(Set.of("s1", "unknown"), List.of())),
                inspector("skipped", InspectionResult.completed(Set.of("s1"), List.of()))));

        assertThatThrownBy(() -> service.inspect(
                request(ContentSegment.PrivacyProcessingStatus.UNKNOWN), null, recordingListener))
                .isInstanceOfSatisfying(InspectionException.class, failure -> {
                    InspectionReport.Outcome outcome = failure.report().orElseThrow().outcomes().get(0);
                    assertThat(outcome.result().failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
                    assertThat(finished).containsExactly(new Execution("invalid", outcome, null));
                });
        assertThat(started).containsExactly("invalid");
    }

    @Test
    void doesNotReportInspectorsStoppedByThePrivacyRequirement() {
        InspectionService service = new InspectionService(List.of(
                inspector("requires-privacy", InspectionResult.completed(Set.of("s1"), List.of()), true)));

        assertThatThrownBy(() -> service.inspect(
                request(ContentSegment.PrivacyProcessingStatus.UNKNOWN), null, recordingListener))
                .isInstanceOfSatisfying(InspectionException.class, failure ->
                        assertThat(failure.code()).isEqualTo(InspectionFailureCode.PRIVACY_PROCESSING_REQUIRED));
        assertThat(started).isEmpty();
        assertThat(finished).isEmpty();
    }

    @Test
    void listenerExceptionsDoNotChangeTheInspection() {
        InspectionService service = new InspectionService(List.of(
                inspector("start-fails", InspectionResult.completed(Set.of("s1"), List.of())),
                inspector("finish-fails", InspectionResult.completed(Set.of("s1"), List.of()))));
        InspectorExecutionListener failingListener = inspectorId -> {
            if (inspectorId.equals("start-fails")) {
                throw new IllegalStateException("start");
            }
            return (outcome, decision) -> {
                throw new IllegalStateException("finish");
            };
        };

        InspectionReport report = service.inspect(
                request(ContentSegment.PrivacyProcessingStatus.UNKNOWN), null, failingListener);

        assertThat(report.decision()).isEqualTo(InspectionDecision.ALLOW);
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("start-fails", "finish-fails");
    }
}
