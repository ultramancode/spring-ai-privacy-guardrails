package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
                                ContentSegment.Source.USER,
                                ContentSegment.Role.USER,
                                ContentSegment.Representation.RAW,
                                "private raw text")),
                InspectionLimits.defaults());
    }

    private ContentInspector inspector(
            String id, Function<InspectionRequest, InspectionResult> fn) {
        return new ContentInspector() {
            public String providerId() {
                return id;
            }

            public boolean requiresProtectedContent() {
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
    void emptyCoverageCannotPass() {
        InspectionResult providerResult = InspectionResult.completed(Set.of(), List.of());
        InspectionService service = new InspectionService(List.of(inspector("one", request -> providerResult)));

        InspectionReport report = service.inspect(request());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes().get(0).result().failure())
                .isEqualTo(InspectionFailure.INCOMPLETE);
    }

    @Test
    void policySeesProviderEvidenceBeforeIncompleteCoverageIsReported() {
        InspectionFinding finding =
                new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult providerResult = InspectionResult.completed(Set.of(), List.of(finding));
        AtomicReference<InspectionResult> evaluated = new AtomicReference<>();
        InspectionService service = new InspectionService(
                List.of(inspector("one", request -> providerResult)),
                result -> {
                    evaluated.set(result);
                    return InspectionDecision.ALLOW;
                },
                InspectionFailurePolicy.FAIL_OPEN);

        InspectionReport report = service.inspect(request());

        assertThat(evaluated.get()).isEqualTo(providerResult);
        assertThat(report.allowedAfterFailure()).isTrue();
        assertThat(report.outcomes().get(0).result().failure()).isEqualTo(InspectionFailure.INCOMPLETE);
        assertThat(report.outcomes().get(0).result().findings()).containsExactly(finding);
    }

    @ParameterizedTest(name = "failure={0}, thrown={1}")
    @CsvSource({
            "CANCELLED, false", "CANCELLED, true",
            "DISCLOSURE_DENIED, false", "DISCLOSURE_DENIED, true",
            "CONFIGURATION, false", "CONFIGURATION, true",
            "UNSUPPORTED_CONTENT, false", "UNSUPPORTED_CONTENT, true"
    })
    void nonOverridableProviderFailuresBypassPolicyAndFailOpen(InspectionFailure failure, boolean thrown) {
        AtomicInteger evaluations = new AtomicInteger();
        InspectionService service = new InspectionService(
                List.of(inspector("one", request -> {
                    if (thrown) {
                        throw new InspectionException(failure);
                    }
                    return InspectionResult.failed(failure);
                })),
                result -> {
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
        InspectionResult providerResult = InspectionResult.failed(InspectionFailure.TIMEOUT);
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
                InspectionFailure.TIMEOUT, Set.of(), List.of(finding));
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

    @Test
    void disclosureIsPreflightedForAllProvidersAndCannotFailOpen() {
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
        assertThatThrownBy(() -> service.inspect(request()))
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

    @Test
    void invalidProviderResultsFailClosed() {
        for (InspectionResult result :
                new InspectionResult[] {
                    null,
                    InspectionResult.completed(Set.of("other"), List.of()),
                    InspectionResult.completed(
                            Set.of("s1"),
                            List.of(
                                    new InspectionFinding(
                                            "other",
                                            InspectionFinding.Category.PROMPT_ATTACK,
                                            "x",
                                            null)))
                }) {
            assertThat(
                            new InspectionService(List.of(inspector("one", r -> result)))
                                    .inspect(request())
                                    .decision())
                    .isEqualTo(InspectionDecision.BLOCK);
        }
    }

    @Test
    void validatesLimitsAndDoesNotExposeRequestText() {
        assertThat(request().toString()).doesNotContain("private raw text");
        assertThat(request().segments().get(0).toString()).doesNotContain("private raw text");
        assertThatThrownBy(() -> new InspectionLimits(1, 1, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new InspectionRequest(
                                        request().segments(),
                                        new InspectionLimits(1, 1, 1, Duration.ofSeconds(1))))
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
