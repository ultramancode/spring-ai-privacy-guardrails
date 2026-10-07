package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionPolicyContractTest {

    private final InspectionFinding finding = new InspectionFinding(
            "s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", 0.9);

    private InspectionRequest request(Duration timeout) {
        return new InspectionRequest(List.of(new ContentSegment("s1", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.UNKNOWN, "private raw text")),
                new InspectionLimits(1, 100, timeout));
    }

    private ContentInspector inspector(InspectionResult result) {
        return new ContentInspector() {
            public String inspectorId() { return "local"; }
            public boolean requiresPrivacyProcessedContent() { return false; }
            public InspectionResult inspect(InspectionRequest request) { return result; }
        };
    }

    @ParameterizedTest
    @EnumSource(InspectionFailurePolicy.class)
    void policyRuntimeFailureIsSanitizedAndRetainsTheCollectedReport(InspectionFailurePolicy failurePolicy) {
        InspectionService service = new InspectionService(List.of(inspector(
                InspectionResult.completed(Set.of("s1"), List.of(finding)))),
                (id, findings) -> { throw new IllegalStateException("private raw text"); }, failurePolicy);
        assertThatThrownBy(() -> service.inspect(request(Duration.ofSeconds(10))))
                .isInstanceOfSatisfying(InspectionException.class, failure -> {
                    assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
                    assertThat(failure.report()).hasValueSatisfying(report -> {
                        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                        assertThat(report.outcomes()).singleElement().satisfies(outcome ->
                                assertThat(outcome.result().findings()).containsExactly(finding));
                    });
                }).hasMessageNotContaining("private raw text").hasNoCause();
    }

    @Test
    void invalidRequirementPreflightIsSanitizedBeforeAnyInspectorRuns() {
        ContentInspector invalid = new ContentInspector() {
            public String inspectorId() { return "invalid"; }
            public boolean requiresPrivacyProcessedContent() {
                throw new IllegalStateException("private raw text");
            }
            public InspectionResult inspect(InspectionRequest request) {
                throw new AssertionError("Preflight failure must prevent inspection");
            }
        };
        InspectionService service = new InspectionService(List.of(invalid),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
        assertThatThrownBy(() -> service.inspect(request(Duration.ofSeconds(10))))
                .isInstanceOfSatisfying(InspectionException.class, failure -> {
                    assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
                    assertThat(failure.report()).hasValueSatisfying(report -> {
                        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                        assertThat(report.outcomes()).isEmpty();
                    });
                }).hasMessageNotContaining("private raw text").hasNoCause();
    }

    @Test
    void nullPolicyDecisionCannotEscapeAsAnUnsanitizedNullPointerException() {
        InspectionService service = new InspectionService(List.of(inspector(
                InspectionResult.completed(Set.of("s1"), List.of()))),
                (id, findings) -> null, InspectionFailurePolicy.FAIL_OPEN);
        assertThatThrownBy(() -> service.inspect(request(Duration.ofSeconds(10))))
                .isInstanceOfSatisfying(InspectionException.class, failure ->
                        assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT))
                .hasNoCause();
    }

    @ParameterizedTest
    @EnumSource(InspectionFailurePolicy.class)
    void policyDeadlineDoesNotReplaceAnExistingFailureOrDiscardEvidence(
            InspectionFailurePolicy failurePolicy) {
        AtomicReference<InspectionRequest> activeRequest = new AtomicReference<>();
        InspectionResult partial = InspectionResult.failed(InspectionFailureCode.MODEL_ERROR,
                Set.of(), List.of(finding));
        InspectionService service = new InspectionService(List.of(inspector(partial)), (id, findings) -> {
            assertThat(findings).containsExactly(finding);
            waitUntilDeadlineExpires(activeRequest.get());
            return InspectionDecision.ALLOW;
        }, failurePolicy);
        InspectionRequest request = request(Duration.ofSeconds(2));
        activeRequest.set(request);
        InspectionReport report = service.inspect(request);
        assertThat(report.outcomes()).singleElement().satisfies(outcome -> {
            assertThat(outcome.result().failureCode()).isEqualTo(InspectionFailureCode.MODEL_ERROR);
            assertThat(outcome.result().findings()).containsExactly(finding);
            assertThat(outcome.result().completedSegmentIds()).isEmpty();
        });
        assertThat(report.decision()).isEqualTo(failurePolicy == InspectionFailurePolicy.FAIL_OPEN
                ? InspectionDecision.ALLOW : InspectionDecision.BLOCK);
    }

    @Test
    void interruptionPrecedesAClassifiedPolicyException() {
        InspectionService service = new InspectionService(List.of(inspector(
                InspectionResult.completed(Set.of("s1"), List.of(finding)))),
                (id, findings) -> {
                    Thread.currentThread().interrupt();
                    throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
                }, InspectionFailurePolicy.FAIL_OPEN);

        try {
            assertThatThrownBy(() -> service.inspect(request(Duration.ofSeconds(10))))
                    .isInstanceOfSatisfying(InspectionException.class, failure -> {
                        assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);
                        assertThat(failure.report()).hasValueSatisfying(report -> {
                            assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                            assertThat(report.outcomes()).singleElement().satisfies(outcome ->
                                    assertThat(outcome.result().findings()).containsExactly(finding));
                        });
                    }).hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionPrecedesAClassifiedPreflightException() {
        ContentInspector invalid = new ContentInspector() {
            public String inspectorId() { return "invalid"; }
            public boolean requiresPrivacyProcessedContent() {
                Thread.currentThread().interrupt();
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            public InspectionResult inspect(InspectionRequest request) {
                throw new AssertionError("Preflight failure must prevent inspection");
            }
        };
        InspectionService service = new InspectionService(List.of(invalid),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        try {
            assertThatThrownBy(() -> service.inspect(request(Duration.ofSeconds(10))))
                    .isInstanceOfSatisfying(InspectionException.class, failure -> {
                        assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);
                        assertThat(failure.report()).hasValueSatisfying(report -> {
                            assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                            assertThat(report.outcomes()).isEmpty();
                        });
                    }).hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void expiredDeadlineNeverDowngradesADeclaredHardFailure() {
        // A provider may return after the deadline. A hard failure must retain its precedence.
        ContentInspector provider = new ContentInspector() {
            public String inspectorId() { return "hard"; }
            public boolean requiresPrivacyProcessedContent() { return false; }
            public InspectionResult inspect(InspectionRequest request) {
                waitUntilDeadlineExpires(request);
                return InspectionResult.failed(InspectionFailureCode.CONFIGURATION);
            }
        };
        InspectionService service = new InspectionService(List.of(provider),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
        assertThatThrownBy(() -> service.inspect(request(Duration.ofMillis(500))))
                .isInstanceOfSatisfying(InspectionException.class, failure ->
                        assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CONFIGURATION));
    }

    private void waitUntilDeadlineExpires(InspectionRequest request) {
        while (true) {
            try {
                LockSupport.parkNanos(request.remaining().toNanos());
            } catch (InspectionException expired) {
                assertThat(expired.failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
                return;
            }
        }
    }
}
