package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
                .isInstanceOfSatisfying(InspectionException.class, ex -> assertThat(ex.failureCode()).isEqualTo(failure));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void perInspectorFailOpenContinuesToLaterInspectors(boolean thrown) {
        AtomicInteger laterCalls = new AtomicInteger();
        ContentInspector optional = inspector("optional", request -> {
            if (thrown) {
                throw new InspectionException(InspectionFailureCode.TIMEOUT);
            }
            return InspectionResult.failed(InspectionFailureCode.TIMEOUT);
        });
        ContentInspector required = inspector("required", request -> {
            laterCalls.incrementAndGet();
            return safe();
        });
        InspectionService service = new InspectionService(
                List.of(optional, required), InspectionPolicy.blockFindings(),
                InspectionFailurePolicy.FAIL_CLOSED,
                Map.of("optional", InspectionFailurePolicy.FAIL_OPEN));

        InspectionReport report = service.inspect(request());

        assertThat(report.allowedAfterFailure()).isTrue();
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("optional", "required");
        assertThat(report.outcomes().get(0).result().failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
        assertThat(laterCalls).hasValue(1);
    }

    @Test
    void perInspectorFailClosedOverridesCommonFailOpen() {
        AtomicInteger laterCalls = new AtomicInteger();
        ContentInspector required = inspector("required", request ->
                InspectionResult.failed(InspectionFailureCode.TRANSPORT_ERROR));
        ContentInspector later = inspector("later", request -> {
            laterCalls.incrementAndGet();
            return safe();
        });
        InspectionService service = new InspectionService(
                List.of(required, later), InspectionPolicy.blockFindings(),
                InspectionFailurePolicy.FAIL_OPEN,
                Map.of("required", InspectionFailurePolicy.FAIL_CLOSED));

        InspectionReport report = service.inspect(request());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("required");
        assertThat(laterCalls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(InspectionFailurePolicy.class)
    void inspectorsWithoutOverridesUseCommonFailurePolicy(InspectionFailurePolicy commonPolicy) {
        InspectionService service = new InspectionService(
                List.of(
                        inspector("optional", request -> InspectionResult.failed(InspectionFailureCode.TIMEOUT)),
                        inspector("other", request -> InspectionResult.failed(InspectionFailureCode.TRANSPORT_ERROR))),
                InspectionPolicy.blockFindings(), commonPolicy,
                Map.of("optional", InspectionFailurePolicy.FAIL_OPEN));

        InspectionReport report = service.inspect(request());

        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("optional", "other");
        if (commonPolicy == InspectionFailurePolicy.FAIL_CLOSED) {
            assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        } else {
            assertThat(report.allowedAfterFailure()).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(value = InspectionFailureCode.class, names = {
            "CANCELLED", "LIMIT_EXCEEDED", "DISCLOSURE_DENIED", "INVALID_RESULT",
            "CONFIGURATION", "UNSUPPORTED_CONTENT"
    })
    void perInspectorFailOpenCannotOverrideHardFailure(InspectionFailureCode failureCode) {
        AtomicInteger policyCalls = new AtomicInteger();
        InspectionService service = new InspectionService(
                List.of(inspector("optional", request -> InspectionResult.failed(failureCode))),
                (id, findings) -> {
                    policyCalls.incrementAndGet();
                    return InspectionDecision.ALLOW;
                },
                InspectionFailurePolicy.FAIL_CLOSED,
                Map.of("optional", InspectionFailurePolicy.FAIL_OPEN));

        assertThatThrownBy(() -> service.inspect(request()))
                .isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failureCode()).isEqualTo(failureCode));
        assertThat(policyCalls).hasValue(0);
    }

    @Test
    void perInspectorFailOpenRetainsFindingsAndCannotOverrideContentBlock() {
        InspectionFinding finding = new InspectionFinding(
                "s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult partial = InspectionResult.failed(
                InspectionFailureCode.TIMEOUT, Set.of("s1"), List.of(finding));
        AtomicInteger laterCalls = new AtomicInteger();
        InspectionService service = new InspectionService(
                List.of(
                        inspector("optional", request -> partial),
                        inspector("later", request -> {
                            laterCalls.incrementAndGet();
                            return safe();
                        })),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_CLOSED,
                Map.of("optional", InspectionFailurePolicy.FAIL_OPEN));

        InspectionReport report = service.inspect(request());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes()).hasSize(1);
        assertThat(report.outcomes().get(0).result()).isEqualTo(partial);
        assertThat(laterCalls).hasValue(0);
    }

    @Test
    void failurePolicyOverridesAreFixedAtConstruction() {
        Map<String, InspectionFailurePolicy> overrides = new LinkedHashMap<>();
        overrides.put("optional", InspectionFailurePolicy.FAIL_OPEN);
        InspectionService service = new InspectionService(
                List.of(inspector("optional", request -> InspectionResult.failed(InspectionFailureCode.TIMEOUT))),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_CLOSED, overrides);

        overrides.put("optional", InspectionFailurePolicy.FAIL_CLOSED);

        assertThat(service.inspect(request()).allowedAfterFailure()).isTrue();
    }

    @Test
    void failurePolicyOverridesMustReferenceConfiguredInspectors() {
        assertThatThrownBy(() -> new InspectionService(
                List.of(inspector("configured", request -> safe())),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_CLOSED,
                Map.of("unknown", InspectionFailurePolicy.FAIL_OPEN)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Failure policy overrides must reference configured inspector IDs");
    }

    @Test
    void failOpenOverrideDoesNotGiveLaterInspectorsANewTimeBudget() {
        AtomicInteger calls = new AtomicInteger();
        InspectionService service = new InspectionService(
                List.of(
                        inspector("optional", request -> {
                            calls.incrementAndGet();
                            return safe();
                        }),
                        inspector("required", request -> {
                            calls.incrementAndGet();
                            return safe();
                        })),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_CLOSED,
                Map.of("optional", InspectionFailurePolicy.FAIL_OPEN));
        InspectionRequest expired = new InspectionRequest(request().segments(),
                new InspectionLimits(1, 100, Duration.ofNanos(1)));

        InspectionReport report = service.inspect(expired);

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("optional", "required");
        assertThat(report.outcomes()).allSatisfy(outcome ->
                assertThat(outcome.result().failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT));
        assertThat(calls).hasValue(0);
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
                    assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
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
                        new InspectionFinding("other", InspectionFinding.Category.PROMPT_ATTACK, "x", null))),
                InspectionResult.failed(InspectionFailureCode.MODEL_ERROR, Set.of("other"), List.of(finding)),
                InspectionResult.failed(InspectionFailureCode.MODEL_ERROR, Set.of(), List.of(
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
                        assertThat(ex.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
                        InspectionResult normalized = ex.report().orElseThrow().outcomes().get(0).result();
                        assertThat(normalized.status()).isEqualTo(InspectionResult.Status.FAILED);
                        assertThat(normalized.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
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
                        assertThat(ex.failureCode()).isEqualTo(failure);
                        InspectionReport report = ex.report().orElseThrow();
                        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                        assertThat(report.outcomes().get(0).result().failureCode()).isEqualTo(failure);
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
                    assertThat(ex.failureCode()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
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
        assertThatThrownBy(() -> new InspectionLimits(1, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new InspectionRequest(
                                        request().segments(),
                                        new InspectionLimits(1, 1, Duration.ofSeconds(1))))
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

    @Test
    void expiredRequestCannotRunInspectorsOrClaimCompletionWhenFailOpen() {
        AtomicInteger calls = new AtomicInteger();
        InspectionService service = new InspectionService(List.of(inspector("one", ignored -> {
            calls.incrementAndGet();
            return safe();
        })), InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
        InspectionRequest expired = new InspectionRequest(request().segments(),
                new InspectionLimits(1, 100, Duration.ofNanos(1)));

        InspectionReport report = service.inspect(expired);

        assertThat(calls).hasValue(0);
        assertThat(report.allowedAfterFailure()).isTrue();
        assertThat(report.outcomes().get(0).result().failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
        assertThat(report.outcomes().get(0).result().completedSegmentIds()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredDeadlinePreservesReportedFailureAndPreventsLaterInspection(boolean thrown) {
        InspectionFinding finding = new InspectionFinding(
                "s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult failedResult = InspectionResult.failed(
                InspectionFailureCode.MODEL_ERROR, Set.of("s1"), List.of(finding));
        AtomicInteger laterCalls = new AtomicInteger();
        InspectionService service = new InspectionService(List.of(
                inspector("first", request -> {
                    waitUntilDeadlineExpires(request);
                    if (thrown) {
                        throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
                    }
                    return failedResult;
                }),
                inspector("later", request -> {
                    laterCalls.incrementAndGet();
                    return safe();
                })), (id, findings) -> InspectionDecision.ALLOW, InspectionFailurePolicy.FAIL_OPEN);
        InspectionRequest bounded = new InspectionRequest(request().segments(),
                new InspectionLimits(1, 100, Duration.ofMillis(500)));

        InspectionReport report = service.inspect(bounded);

        assertThat(report.allowedAfterFailure()).isTrue();
        assertThat(laterCalls).hasValue(0);
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("first", "later");
        InspectionResult firstResult = report.outcomes().get(0).result();
        assertThat(firstResult.failureCode()).isEqualTo(InspectionFailureCode.MODEL_ERROR);
        if (thrown) {
            assertThat(firstResult.completedSegmentIds()).isEmpty();
            assertThat(firstResult.findings()).isEmpty();
        } else {
            assertThat(firstResult).isEqualTo(failedResult);
        }
        InspectionResult laterResult = report.outcomes().get(1).result();
        assertThat(laterResult.failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
        assertThat(laterResult.completedSegmentIds()).isEmpty();
        assertThat(laterResult.findings()).isEmpty();
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
                        failure -> assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT));
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
        assertThat(report.outcomes().get(0).result().failureCode()).isEqualTo(code);
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED, false", "COMPLETED, true", "FAILED, false", "FAILED, true"})
    void interruptionPreservesInspectorResultAndCannotFailOpen(
            InspectionResult.Status status, boolean interruptInPolicy) {
        InspectionFinding finding = new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult providerResult;
        if (status == InspectionResult.Status.COMPLETED) {
            providerResult = InspectionResult.completed(Set.of("s1"), List.of(finding));
        } else {
            providerResult = InspectionResult.failed(InspectionFailureCode.MODEL_ERROR, Set.of("s1"), List.of(finding));
        }
        ContentInspector inspector = inspector("one", request -> {
            if (!interruptInPolicy) {
                Thread.currentThread().interrupt();
            }
            return providerResult;
        });
        AtomicInteger policyCalls = new AtomicInteger();
        InspectionPolicy policy = (id, findings) -> {
            policyCalls.incrementAndGet();
            if (interruptInPolicy) {
                Thread.currentThread().interrupt();
            }
            return InspectionDecision.ALLOW;
        };
        AtomicInteger laterCalls = new AtomicInteger();
        ContentInspector later = inspector("later", request -> {
            laterCalls.incrementAndGet();
            return safe();
        });
        InspectionService service = new InspectionService(
                List.of(inspector, later), policy, InspectionFailurePolicy.FAIL_OPEN);

        try {
            assertThatThrownBy(() -> service.inspect(request()))
                    .isInstanceOfSatisfying(InspectionException.class, ex -> {
                        assertThat(ex.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);
                        InspectionReport report = ex.report().orElseThrow();
                        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                        assertThat(report.outcomes()).hasSize(1);
                        assertThat(report.outcomes().get(0).result()).isEqualTo(providerResult);
                    });
            assertThat(laterCalls).hasValue(0);
            if (interruptInPolicy) {
                assertThat(policyCalls).hasValue(1);
            } else {
                assertThat(policyCalls).hasValue(0);
            }
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "FAIL_CLOSED, ALLOW, false", "FAIL_OPEN, ALLOW, false",
            "FAIL_CLOSED, BLOCK, false", "FAIL_OPEN, BLOCK, false",
            "FAIL_CLOSED, ALLOW, true", "FAIL_OPEN, ALLOW, true",
            "FAIL_CLOSED, BLOCK, true", "FAIL_OPEN, BLOCK, true"
    })
    void completedResultsSurviveDeadlineExpiryDuringInspectorOrPolicy(
            InspectionFailurePolicy failurePolicy, InspectionDecision contentDecision, boolean expireInPolicy) {
        InspectionFinding finding = new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult providerResult = InspectionResult.completed(Set.of("s1"), List.of(finding));
        InspectionRequest bounded = new InspectionRequest(request().segments(),
                new InspectionLimits(1, 100, Duration.ofMillis(100)));
        InspectionPolicy policy = (id, findings) -> {
            assertThat(findings).containsExactly(finding);
            if (expireInPolicy) {
                waitUntilDeadlineExpires(bounded);
            }
            return contentDecision;
        };
        ContentInspector inspector = inspector("one", request -> {
            if (!expireInPolicy) {
                waitUntilDeadlineExpires(request);
            }
            return providerResult;
        });
        InspectionService service = new InspectionService(List.of(inspector), policy, failurePolicy);

        InspectionReport report = service.inspect(bounded);

        assertThat(report.decision()).isEqualTo(contentDecision);
        assertThat(report.outcomes().get(0).result()).isEqualTo(providerResult);
        assertThat(report.allowedAfterFailure()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"FAIL_CLOSED, false", "FAIL_OPEN, false", "FAIL_CLOSED, true", "FAIL_OPEN, true"})
    void deadlineExpiryPreventsLaterInspectionAndAppliesItsOwnFailurePolicy(
            InspectionFailurePolicy laterFailurePolicy, boolean expireInPolicy) {
        InspectionResult providerResult = safe();
        AtomicInteger laterCalls = new AtomicInteger();
        InspectionRequest bounded = new InspectionRequest(request().segments(),
                new InspectionLimits(1, 100, Duration.ofMillis(100)));
        ContentInspector first = inspector("first", request -> {
            if (!expireInPolicy) {
                waitUntilDeadlineExpires(request);
            }
            return providerResult;
        });
        ContentInspector later = inspector("later", request -> {
            laterCalls.incrementAndGet();
            return safe();
        });
        InspectionPolicy policy = (id, findings) -> {
            if (expireInPolicy && id.equals("first")) {
                waitUntilDeadlineExpires(bounded);
            }
            return InspectionDecision.ALLOW;
        };
        InspectionService service = new InspectionService(List.of(first, later), policy,
                InspectionFailurePolicy.FAIL_CLOSED, Map.of("later", laterFailurePolicy));

        InspectionReport report = service.inspect(bounded);

        assertThat(laterCalls).hasValue(0);
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("first", "later");
        assertThat(report.outcomes().get(0).result()).isEqualTo(providerResult);
        assertThat(report.outcomes().get(1).result())
                .isEqualTo(InspectionResult.failed(InspectionFailureCode.TIMEOUT));
        if (laterFailurePolicy == InspectionFailurePolicy.FAIL_OPEN) {
            assertThat(report.allowedAfterFailure()).isTrue();
        } else {
            assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        }
    }

    @ParameterizedTest
    @EnumSource(value = InspectionFailureCode.class, names = {"MODEL_ERROR", "CONFIGURATION", "INVALID_RESULT"})
    void interruptedProviderExceptionPreservesItsFailureCode(InspectionFailureCode failureCode) {
        ContentInspector provider = inspector("one", request -> {
            Thread.currentThread().interrupt();
            if (failureCode == InspectionFailureCode.INVALID_RESULT) {
                throw new IllegalStateException("private raw text");
            }
            throw new InspectionException(failureCode);
        });
        InspectionService service = new InspectionService(List.of(provider),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        try {
            assertThatThrownBy(() -> service.inspect(request()))
                    .isInstanceOfSatisfying(InspectionException.class, ex -> {
                        assertThat(ex.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);
                        InspectionReport report = ex.report().orElseThrow();
                        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                        assertThat(report.outcomes()).singleElement().satisfies(outcome ->
                                assertThat(outcome.result().failureCode()).isEqualTo(failureCode));
                    }).hasNoCause().hasMessageNotContaining("private raw text");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
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
