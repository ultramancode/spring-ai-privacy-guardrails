package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionServiceTest {

    private InspectionRequest request() {
        return new InspectionRequest(
                List.of(
                        new ContentSegment(
                                "s1",
                                ContentSegment.Role.USER,
                                ContentSegment.PrivacyProcessingStatus.UNPROCESSED,
                                "private raw text")),
                InspectionLimits.defaults());
    }

    private ContentInspector inspector(
            String id, Function<InspectionRequest, InspectionResult> fn) {
        return new ContentInspector() {
            public String inspectorId() {
                return id;
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                return fn.apply(request);
            }
        };
    }

    private InspectionResult safe() {
        return InspectionResult.completed(Set.of("s1"), List.of());
    }

    @Test
    void completedAndCoveredCanAllow() {
        assertThat(
                        new InspectionService(List.of(inspector("one", r -> safe())))
                                .inspect(request())
                                .decision())
                .isEqualTo(InspectionDecision.ALLOW);
    }

    @Test
    void policyReceivesInstanceIdAndPartialEvidenceWithoutCompletionResponsibilities() {
        InspectionFinding finding =
                new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult partial = InspectionResult.failed(
                InspectionFailureCode.INCOMPLETE, Set.of(), List.of(finding));
        AtomicReference<String> evaluatedId = new AtomicReference<>();
        AtomicReference<List<InspectionFinding>> evaluatedFindings = new AtomicReference<>();
        InspectionService service = new InspectionService(
                List.of(inspector("model-a", request -> partial)),
                (id, findings) -> {
                    evaluatedId.set(id);
                    evaluatedFindings.set(findings);
                    return InspectionDecision.ALLOW;
                },
                InspectionFailurePolicy.FAIL_OPEN);

        InspectionReport report = service.inspect(request());

        assertThat(evaluatedId.get()).isEqualTo("model-a");
        assertThat(evaluatedFindings.get()).containsExactly(finding);
        assertThat(report.allowedAfterFailure()).isTrue();
        assertThat(report.outcomes().get(0).result()).isEqualTo(partial);
    }

    @ParameterizedTest(name = "failure={0}, thrown={1}")
    @CsvSource({
            "CANCELLED, false", "CANCELLED, true",
            "LIMIT_EXCEEDED, false", "LIMIT_EXCEEDED, true",
            "DISCLOSURE_DENIED, false", "DISCLOSURE_DENIED, true",
            "CONFIGURATION, false", "CONFIGURATION, true",
            "UNSUPPORTED_CONTENT, false", "UNSUPPORTED_CONTENT, true",
            "INVALID_RESULT, false", "INVALID_RESULT, true"
    })
    void nonOverridableProviderFailuresBypassPolicyAndFailOpen(InspectionFailureCode failure, boolean thrown) {
        AtomicInteger evaluations = new AtomicInteger();
        InspectionService service = new InspectionService(
                List.of(inspector("one", request -> {
                    if (thrown) {
                        throw new InspectionException(failure);
                    }
                    return InspectionResult.failed(failure);
                })),
                (id, findings) -> {
                    evaluations.incrementAndGet();
                    return InspectionDecision.ALLOW;
                },
                InspectionFailurePolicy.FAIL_OPEN);

        assertThatThrownBy(() -> service.inspect(request()))
                .isInstanceOfSatisfying(InspectionException.class, ex -> assertThat(ex.failure()).isEqualTo(failure));
        assertThat(evaluations).hasValue(0);
    }

    @Test
    void explicitFailOpenRetainsFailedStatus() {
        InspectionResult providerResult = InspectionResult.failed(InspectionFailureCode.TIMEOUT);
        InspectionService service = new InspectionService(
                List.of(inspector("one", request -> providerResult)),
                InspectionPolicy.blockFindings(),
                InspectionFailurePolicy.FAIL_OPEN);

        InspectionReport report = service.inspect(request());

        assertThat(report.allowedAfterFailure()).isTrue();
        assertThat(report.outcomes().get(0).result().status())
                .isEqualTo(InspectionResult.Status.FAILED);
    }

    @Test
    void findingBlocksEvenWhenLaterWorkFailedAndFailOpenEnabled() {
        InspectionFinding finding =
                new InspectionFinding(
                        "s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult providerResult = InspectionResult.failed(
                InspectionFailureCode.TIMEOUT, Set.of(), List.of(finding));
        InspectionService service = new InspectionService(
                List.of(inspector("one", request -> providerResult)),
                InspectionPolicy.blockFindings(),
                InspectionFailurePolicy.FAIL_OPEN);

        InspectionReport report = service.inspect(request());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
    }

    @Test
    void confirmedBlockShortCircuits() {
        InspectionFinding finding =
                new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult blockedResult = InspectionResult.completed(Set.of("s1"), List.of(finding));
        ContentInspector blockingInspector = inspector("one", request -> blockedResult);
        AtomicInteger laterInspectorCalls = new AtomicInteger();
        ContentInspector laterInspector = inspector("two", request -> {
            laterInspectorCalls.incrementAndGet();
            return safe();
        });
        InspectionService service = new InspectionService(List.of(blockingInspector, laterInspector));

        InspectionReport report = service.inspect(request());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(laterInspectorCalls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = ContentSegment.PrivacyProcessingStatus.class, names = {"UNKNOWN", "UNPROCESSED"})
    void disclosureIsPreflightedForAllProvidersAndCannotFailOpen(ContentSegment.PrivacyProcessingStatus status) {
        AtomicInteger calls = new AtomicInteger();
        ContentInspector remote = new ContentInspector() {
            @Override
            public String inspectorId() {
                return "remote";
            }

            @Override
            public boolean requiresPrivacyProcessedContent() {
                return true;
            }

            @Override
            public InspectionResult inspect(InspectionRequest request) {
                calls.incrementAndGet();
                return safe();
            }
        };
        InspectionService service =
                new InspectionService(
                        List.of(
                                inspector(
                                        "first",
                                        r -> {
                                            calls.incrementAndGet();
                                            return safe();
                                        }),
                                remote),
                        InspectionPolicy.blockFindings(),
                        InspectionFailurePolicy.FAIL_OPEN);
        InspectionRequest mixedRequest = new InspectionRequest(List.of(
                new ContentSegment("s1", ContentSegment.Role.USER,
                        ContentSegment.PrivacyProcessingStatus.PROCESSED, "processed text"),
                new ContentSegment("s2", ContentSegment.Role.TOOL, status, "other text")),
                InspectionLimits.defaults());
        assertThatThrownBy(() -> service.inspect(mixedRequest))
                .isInstanceOf(InspectionException.class)
                .hasMessageContaining("DISCLOSURE_DENIED");
        assertThat(calls).hasValue(0);
    }

    @Test
    void interruptionIsNeverAllowed() {
        InspectionService service = new InspectionService(
                List.of(inspector("one", request -> safe())),
                InspectionPolicy.blockFindings(),
                InspectionFailurePolicy.FAIL_OPEN);
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> service.inspect(request()))
                    .hasMessageContaining("CANCELLED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @EnumSource(InspectionFailurePolicy.class)
    void unclassifiedProviderExceptionsAreSanitizedAndCannotFailOpen(InspectionFailurePolicy failurePolicy) {
        InspectionService service = new InspectionService(List.of(inspector("one", request -> {
            throw new IllegalStateException("secret customer@example.com");
        })), InspectionPolicy.blockFindings(), failurePolicy);

        assertThatThrownBy(() -> service.inspect(request()))
                .isInstanceOfSatisfying(InspectionException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.report().orElseThrow().toString())
                            .doesNotContain("secret", "customer@example.com");
                });
    }

    @Test
    void fatalErrorsAreNotSwallowed() {
        InspectionService service =
                new InspectionService(
                        List.of(
                                inspector(
                                        "one",
                                        r -> {
                                            throw new AssertionError("fatal");
                                        })));
        assertThatThrownBy(() -> service.inspect(request())).isInstanceOf(AssertionError.class);
    }

    @ParameterizedTest
    @EnumSource(InspectionFailurePolicy.class)
    void invalidResultsCannotFailOpenOrClaimCompletion(InspectionFailurePolicy failurePolicy) {
        InspectionFinding finding =
                new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        for (InspectionResult result : new InspectionResult[] {
                null,
                InspectionResult.completed(Set.of(), List.of()),
                InspectionResult.completed(Set.of(), List.of(finding)),
                InspectionResult.completed(Set.of("other"), List.of(finding)),
                InspectionResult.completed(Set.of("s1"), List.of(
                        new InspectionFinding("other", InspectionFinding.Category.PROMPT_ATTACK, "x", null)))
        }) {
            AtomicInteger policyCalls = new AtomicInteger();
            InspectionService service = new InspectionService(List.of(inspector("one", r -> result)),
                    (id, findings) -> {
                        policyCalls.incrementAndGet();
                        return InspectionDecision.ALLOW;
                    }, failurePolicy);
            assertThatThrownBy(() -> service.inspect(request()))
                    .isInstanceOfSatisfying(InspectionException.class, ex -> {
                        assertThat(ex.failure()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
                        InspectionResult normalized = ex.report().orElseThrow().outcomes().get(0).result();
                        assertThat(normalized.status()).isEqualTo(InspectionResult.Status.FAILED);
                        assertThat(normalized.failure()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
                    });
            assertThat(policyCalls).hasValue(0);
        }
    }

    @ParameterizedTest
    @EnumSource(value = InspectionFailureCode.class, names = {
            "CANCELLED", "LIMIT_EXCEEDED", "DISCLOSURE_DENIED", "CONFIGURATION", "UNSUPPORTED_CONTENT"
    })
    void invalidAssociationsNeverDowngradeHardFailures(InspectionFailureCode failure) {
        for (InspectionResult result : List.of(
                InspectionResult.failed(failure, Set.of("unknown"), List.of()),
                InspectionResult.failed(failure, Set.of(), List.of(
                        new InspectionFinding("unknown", InspectionFinding.Category.PROMPT_ATTACK, "x", null))))) {
            InspectionService service = new InspectionService(List.of(inspector("one", r -> result)),
                    InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
            assertThatThrownBy(() -> service.inspect(request()))
                    .isInstanceOfSatisfying(InspectionException.class, ex -> {
                        assertThat(ex.failure()).isEqualTo(failure);
                        InspectionReport report = ex.report().orElseThrow();
                        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                        assertThat(report.outcomes().get(0).result().failure()).isEqualTo(failure);
                        assertThat(report.toString()).doesNotContain("unknown");
                    });
        }
    }

    @Test
    void hardFailureKeepsEarlierOutcomesAndValidPartialFindings() {
        InspectionFinding finding =
                new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult partial = InspectionResult.failed(
                InspectionFailureCode.LIMIT_EXCEEDED, Set.of(), List.of(finding));
        AtomicInteger laterCalls = new AtomicInteger();
        InspectionService service = new InspectionService(List.of(
                inspector("first", r -> safe()),
                inspector("second", r -> partial),
                inspector("third", r -> { laterCalls.incrementAndGet(); return safe(); })),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
        assertThatThrownBy(() -> service.inspect(request()))
                .isInstanceOfSatisfying(InspectionException.class, ex -> {
                    assertThat(ex.failure()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
                    InspectionReport report = ex.report().orElseThrow();
                    assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                            .containsExactly("first", "second");
                    assertThat(report.outcomes().get(1).result()).isEqualTo(partial);
                    assertThat(ex.getCause()).isNull();
                });
        assertThat(laterCalls).hasValue(0);
    }

    @Test
    void validatesLimitsAndDoesNotExposeRequestText() {
        assertThat(request().toString()).doesNotContain("private raw text");
        assertThat(request().segments().get(0).toString()).doesNotContain("private raw text");
        assertThatThrownBy(() -> new InspectionLimits(1, 1, 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new InspectionRequest(
                                        request().segments(),
                                        new InspectionLimits(1, 1, 10, Duration.ofSeconds(1))))
                .hasMessageContaining("LIMIT_EXCEEDED");
        assertThatThrownBy(
                        () ->
                                new InspectionRequest(
                                        List.of(
                                                request().segments().get(0),
                                                request().segments().get(0)),
                                        InspectionLimits.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyOrDuplicateInspectorConfigurationsAreRejected() {
        assertThatThrownBy(() -> new InspectionService(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new InspectionService(
                                        List.of(
                                                inspector("same", r -> safe()),
                                                inspector("same", r -> safe()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(InspectionFailurePolicy.class)
    void findingBudgetIsEnforcedAcrossInspectorsBeforePolicy(InspectionFailurePolicy failurePolicy) {
        InspectionFinding finding = new InspectionFinding(
                "s1", InspectionFinding.Category.POLICY_VIOLATION, "match", null);
        InspectionResult evidence = InspectionResult.completed(Set.of("s1"), List.of(finding, finding));
        AtomicInteger policyCalls = new AtomicInteger();
        AtomicInteger laterCalls = new AtomicInteger();
        InspectionService service = new InspectionService(List.of(
                inspector("first", ignored -> evidence),
                inspector("second", ignored -> evidence),
                inspector("third", ignored -> { laterCalls.incrementAndGet(); return safe(); })),
                (id, findings) -> { policyCalls.incrementAndGet(); return InspectionDecision.ALLOW; },
                failurePolicy);
        InspectionRequest bounded = new InspectionRequest(request().segments(),
                new InspectionLimits(1, 100, 3, Duration.ofSeconds(10)));

        assertThatThrownBy(() -> service.inspect(bounded))
                .isInstanceOfSatisfying(InspectionException.class, failure -> {
                    assertThat(failure.failure()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
                    List<InspectionReport.Outcome> outcomes = failure.report().orElseThrow().outcomes();
                    assertThat(outcomes).extracting(InspectionReport.Outcome::inspectorId)
                            .containsExactly("first", "second");
                    assertThat(outcomes.get(0).result().findings()).hasSize(2);
                    assertThat(outcomes.get(1).result().findings()).containsExactly(finding);
                });
        assertThat(policyCalls).hasValue(1);
        assertThat(laterCalls).hasValue(0);
    }

    @Test
    void largeFindingResultsWithinConfiguredBudgetAreRetained() {
        InspectionFinding finding = new InspectionFinding(
                "s1", InspectionFinding.Category.POLICY_VIOLATION, "match", null);
        List<InspectionFinding> findings = Collections.nCopies(10_001, finding);
        InspectionService service = new InspectionService(List.of(inspector("many", ignored ->
                InspectionResult.completed(Set.of("s1"), findings))));
        InspectionRequest request = new InspectionRequest(request().segments(),
                new InspectionLimits(10_001, 10_000_001, 10_001, Duration.ofSeconds(10)));

        InspectionReport report = service.inspect(request);

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes().get(0).result().status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(report.outcomes().get(0).result().findings()).hasSize(10_001);
    }

    @Test
    void expiredRequestCannotRunInspectorsOrClaimCompletionWhenFailOpen() {
        AtomicInteger calls = new AtomicInteger();
        InspectionService service = new InspectionService(List.of(inspector("one", ignored -> {
            calls.incrementAndGet();
            return safe();
        })), InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
        InspectionRequest expired = new InspectionRequest(request().segments(),
                new InspectionLimits(1, 100, 1, Duration.ofNanos(1)));

        InspectionReport report = service.inspect(expired);

        assertThat(calls).hasValue(0);
        assertThat(report.allowedAfterFailure()).isTrue();
        assertThat(report.outcomes().get(0).result().failure()).isEqualTo(InspectionFailureCode.TIMEOUT);
        assertThat(report.outcomes().get(0).result().completedSegmentIds()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nullFindings", "invalidId", "nanScore", "inconsistentCompletion"})
    void resultConstructionFailuresCannotBecomeOperationalFailOpen(String invalidCase) {
        ContentInspector invalid = inspector("invalid", request -> switch (invalidCase) {
            case "nullFindings" -> InspectionResult.completed(Set.of("s1"), null);
            case "invalidId" -> InspectionResult.completed(Set.of("bad id"), List.of());
            case "nanScore" -> InspectionResult.completed(Set.of("s1"), List.of(
                    new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", Double.NaN)));
            case "inconsistentCompletion" -> new InspectionResult(InspectionResult.Status.COMPLETED,
                    Set.of("s1"), List.of(), InspectionFailureCode.TIMEOUT);
            default -> throw new AssertionError("Unknown test case");
        });
        AtomicInteger policyCalls = new AtomicInteger();
        InspectionService service = new InspectionService(List.of(invalid), (id, findings) -> {
            policyCalls.incrementAndGet();
            return InspectionDecision.ALLOW;
        }, InspectionFailurePolicy.FAIL_OPEN);

        assertThatThrownBy(() -> service.inspect(request()))
                .isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failure()).isEqualTo(InspectionFailureCode.INVALID_RESULT));
        assertThat(policyCalls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = InspectionFailureCode.class, names = {
            "TIMEOUT", "TRANSPORT_ERROR", "HTTP_ERROR", "MODEL_ERROR", "INVALID_RESPONSE", "INCOMPLETE"
    })
    void explicitlyClassifiedOperationalFailuresCanFailOpen(InspectionFailureCode code) {
        ContentInspector classified = inspector("classified", request -> {
            throw new InspectionException(code);
        });
        InspectionService service = new InspectionService(List.of(classified),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        InspectionReport report = service.inspect(request());

        assertThat(report.allowedAfterFailure()).isTrue();
        assertThat(report.outcomes().get(0).result().failure()).isEqualTo(code);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptedCompletionRetainsEvidenceAndCannotFailOpen(boolean interruptInPolicy) {
        InspectionFinding finding = new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        ContentInspector inspector = inspector("one", request -> {
            if (!interruptInPolicy) {
                Thread.currentThread().interrupt();
            }
            return InspectionResult.completed(Set.of("s1"), List.of(finding));
        });
        InspectionPolicy policy = (id, findings) -> {
            if (interruptInPolicy) {
                Thread.currentThread().interrupt();
            }
            return InspectionDecision.ALLOW;
        };
        InspectionService service = new InspectionService(List.of(inspector), policy, InspectionFailurePolicy.FAIL_OPEN);

        try {
            assertThatThrownBy(() -> service.inspect(request()))
                    .isInstanceOfSatisfying(InspectionException.class, ex -> {
                        assertThat(ex.failure()).isEqualTo(InspectionFailureCode.CANCELLED);
                        InspectionReport report = ex.report().orElseThrow();
                        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                        assertThat(report.outcomes()).hasSize(1);
                        InspectionResult result = report.outcomes().get(0).result();
                        assertThat(result.status()).isEqualTo(InspectionResult.Status.FAILED);
                        assertThat(result.failure()).isEqualTo(InspectionFailureCode.CANCELLED);
                        assertThat(result.completedSegmentIds()).containsExactly("s1");
                        assertThat(result.findings()).containsExactly(finding);
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @CsvSource({"FAIL_CLOSED, ALLOW", "FAIL_OPEN, ALLOW", "FAIL_CLOSED, BLOCK", "FAIL_OPEN, BLOCK"})
    void policyCompletionAfterDeadlineRetainsEvidenceAndAppliesFailurePolicy(
            InspectionFailurePolicy failurePolicy, InspectionDecision contentDecision) {
        InspectionFinding finding = new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionRequest bounded = new InspectionRequest(request().segments(),
                new InspectionLimits(1, 100, 1, Duration.ofMillis(100)));
        InspectionPolicy slowPolicy = (id, findings) -> {
            assertThat(findings).containsExactly(finding);
            while (true) {
                try {
                    LockSupport.parkNanos(bounded.remaining().toNanos());
                } catch (InspectionException expired) {
                    assertThat(expired.failure()).isEqualTo(InspectionFailureCode.TIMEOUT);
                    break;
                }
            }
            return contentDecision;
        };
        InspectionService service = new InspectionService(List.of(inspector("one", request ->
                InspectionResult.completed(Set.of("s1"), List.of(finding)))), slowPolicy, failurePolicy);

        InspectionReport report = service.inspect(bounded);

        InspectionResult result = report.outcomes().get(0).result();
        assertThat(result.status()).isEqualTo(InspectionResult.Status.FAILED);
        assertThat(result.failure()).isEqualTo(InspectionFailureCode.TIMEOUT);
        assertThat(result.completedSegmentIds()).containsExactly("s1");
        assertThat(result.findings()).containsExactly(finding);
        if (failurePolicy == InspectionFailurePolicy.FAIL_OPEN && contentDecision == InspectionDecision.ALLOW) {
            assertThat(report.allowedAfterFailure()).isTrue();
        } else {
            assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        }
    }
}
