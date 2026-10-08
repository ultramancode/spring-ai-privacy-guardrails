package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionServiceFailureTest {

    private final InspectionFinding finding = new InspectionFinding(
            "s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", 0.9);

    private InspectionRequest request(Duration timeout) {
        return new InspectionRequest(List.of(new ContentSegment("s1", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.UNKNOWN, "private raw text")),
                new InspectionLimits(1, 100, timeout));
    }

    private ContentInspector inspector(InspectionResult result) {
        return new ContentInspector() {
            public String inspectorId() {
                return "local";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                return result;
            }
        };
    }

    @ParameterizedTest
    @EnumSource(InspectionFailurePolicy.class)
    void policyRuntimeFailureIsSanitizedAndRetainsTheCollectedReport(
            InspectionFailurePolicy failurePolicy) {
        InspectionResult result =
                InspectionResult.completed(Set.of("s1"), List.of(finding));
        InspectionPolicy failingPolicy = (inspectorId, findings) -> {
            throw new IllegalStateException("private raw text");
        };
        InspectionService service = new InspectionService(
                List.of(inspector(result)), failingPolicy, failurePolicy);

        assertThatThrownBy(() -> service.inspect(request(Duration.ofSeconds(10))))
                .isInstanceOfSatisfying(InspectionException.class, failure -> {
                    assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT);

                    InspectionReport report = failure.report().orElseThrow();
                    assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                    assertThat(report.outcomes()).hasSize(1);
                    assertThat(report.outcomes().get(0).result().findings()).containsExactly(finding);
                })
                .hasMessageNotContaining("private raw text")
                .hasNoCause();
    }

    @Test
    void nullPolicyDecisionIsRejectedAsInvalidResult() {
        InspectionResult result = InspectionResult.completed(Set.of("s1"), List.of());
        InspectionService service = new InspectionService(List.of(inspector(result)),
                (inspectorId, findings) -> null, InspectionFailurePolicy.FAIL_OPEN);
        assertThatThrownBy(() -> service.inspect(request(Duration.ofSeconds(10))))
                .isInstanceOfSatisfying(InspectionException.class, failure ->
                        assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT))
                .hasNoCause();
    }

    @ParameterizedTest
    @EnumSource(InspectionFailurePolicy.class)
    void deadlineExpiryDuringPolicyEvaluationPreservesFailedResult(
            InspectionFailurePolicy failurePolicy) {
        AtomicInteger policyCalls = new AtomicInteger();
        InspectionResult partial = InspectionResult.failed(InspectionFailureCode.MODEL_ERROR,
                Set.of(), List.of(finding));
        InspectionRequest request = request(Duration.ofSeconds(2));
        InspectionPolicy expiringPolicy = (inspectorId, findings) -> {
            policyCalls.incrementAndGet();
            assertThat(findings).containsExactly(finding);
            waitUntilDeadlineExpires(request);
            return InspectionDecision.ALLOW;
        };
        InspectionService service = new InspectionService(
                List.of(inspector(partial)), expiringPolicy, failurePolicy);

        InspectionReport report = service.inspect(request);
        assertThat(policyCalls).hasValue(1);
        assertThat(report.outcomes()).hasSize(1);
        InspectionResult retainedResult = report.outcomes().get(0).result();
        assertThat(retainedResult).isEqualTo(partial);
        assertThat(report.decision()).isEqualTo(failurePolicy == InspectionFailurePolicy.FAIL_OPEN
                ? InspectionDecision.ALLOW : InspectionDecision.BLOCK);
    }

    @Test
    void interruptionTakesPrecedenceOverPolicyFailure() {
        InspectionResult result =
                InspectionResult.completed(Set.of("s1"), List.of(finding));
        InspectionPolicy interruptingPolicy = (inspectorId, findings) -> {
            Thread.currentThread().interrupt();
            throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
        };
        InspectionService service = new InspectionService(
                List.of(inspector(result)), interruptingPolicy, InspectionFailurePolicy.FAIL_OPEN);

        try {
            assertThatThrownBy(() -> service.inspect(request(Duration.ofSeconds(10))))
                    .isInstanceOfSatisfying(InspectionException.class, failure -> {
                        assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);

                        InspectionReport report = failure.report().orElseThrow();
                        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                        assertThat(report.outcomes()).hasSize(1);
                        assertThat(report.outcomes().get(0).result()).isEqualTo(result);
                    })
                    .hasNoCause();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void configurationFailureIsPreservedAfterDeadlineExpiry() {
        // An inspector may return after the deadline. Preserve its configuration failure.
        ContentInspector inspector = new ContentInspector() {
            public String inspectorId() {
                return "hard";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                waitUntilDeadlineExpires(request);
                return InspectionResult.failed(InspectionFailureCode.CONFIGURATION);
            }
        };
        InspectionService service = new InspectionService(List.of(inspector),
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
