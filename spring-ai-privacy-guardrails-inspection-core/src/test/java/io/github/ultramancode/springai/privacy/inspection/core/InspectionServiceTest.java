package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        ContentInspector remote =
                r -> {
                    calls.incrementAndGet();
                    return safe();
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

    @Test
    void arbitraryProviderExceptionsAreSanitized() {
        InspectionReport report =
                new InspectionService(
                                List.of(
                                        inspector(
                                                "one",
                                                r -> {
                                                    throw new IllegalStateException(
                                                            "secret customer@example.com");
                                                })))
                        .inspect(request());
        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.toString()).doesNotContain("secret", "customer@example.com");
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
}
