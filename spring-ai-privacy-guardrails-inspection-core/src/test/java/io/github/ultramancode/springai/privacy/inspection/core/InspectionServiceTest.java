package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.HashMap;
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
            String inspectorId, Function<InspectionRequest, InspectionResult> inspectionFunction) {
        return new ContentInspector() {
            public String inspectorId() {
                return inspectorId;
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                return inspectionFunction.apply(request);
            }
        };
    }

    private InspectionResult completedWithoutFindings() {
        return InspectionResult.completed(Set.of("s1"), List.of());
    }

    @Test
    void allowsCompletedInspectionWithoutFindings() {
        InspectionService service = new InspectionService(
                List.of(inspector("one", ignoredRequest -> completedWithoutFindings())));

        InspectionReport report = service.inspect(request());

        assertThat(report.decision()).isEqualTo(InspectionDecision.ALLOW);
    }

    @Test
    void contentPolicyReceivesInspectorIdAndPartialFindings() {
        InspectionFinding finding =
                new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult partial = InspectionResult.failed(
                InspectionFailureCode.INCOMPLETE, Set.of(), List.of(finding));
        AtomicReference<String> evaluatedInspectorId = new AtomicReference<>();
        AtomicReference<List<InspectionFinding>> evaluatedFindings = new AtomicReference<>();
        InspectionPolicy allowingPolicy = (inspectorId, findings) -> {
            evaluatedInspectorId.set(inspectorId);
            evaluatedFindings.set(findings);
            return InspectionDecision.ALLOW;
        };

        InspectionService service = new InspectionService(
                List.of(inspector("model-a", ignoredRequest -> partial)),
                allowingPolicy,
                InspectionFailurePolicy.FAIL_OPEN);

        InspectionReport report = service.inspect(request());

        assertThat(evaluatedInspectorId.get()).isEqualTo("model-a");
        assertThat(evaluatedFindings.get()).containsExactly(finding);
        assertThat(report.allowedAfterFailure()).isTrue();
        assertThat(report.outcomes().get(0).result()).isEqualTo(partial);
    }

    @ParameterizedTest(name = "failureCode={0}, throwsException={1}")
    @CsvSource({
            "CANCELLED, false", "CANCELLED, true",
            "LIMIT_EXCEEDED, false", "LIMIT_EXCEEDED, true",
            "DISCLOSURE_DENIED, false", "DISCLOSURE_DENIED, true",
            "CONFIGURATION, false", "CONFIGURATION, true",
            "UNSUPPORTED_CONTENT, false", "UNSUPPORTED_CONTENT, true",
            "INVALID_RESULT, false", "INVALID_RESULT, true"
    })
    void failOpenRejectsIneligibleFailuresBeforeContentPolicy(
            InspectionFailureCode failureCode, boolean throwsException) {
        AtomicInteger policyCalls = new AtomicInteger();
        ContentInspector failingInspector = inspector("one", ignoredRequest -> {
            if (throwsException) {
                throw new InspectionException(failureCode);
            }
            return InspectionResult.failed(failureCode);
        });
        InspectionPolicy allowingPolicy = (inspectorId, findings) -> {
            policyCalls.incrementAndGet();
            return InspectionDecision.ALLOW;
        };
        InspectionService service = new InspectionService(
                List.of(failingInspector), allowingPolicy, InspectionFailurePolicy.FAIL_OPEN);

        assertThatThrownBy(() -> service.inspect(request()))
                .isInstanceOfSatisfying(InspectionException.class, ex -> assertThat(ex.failureCode()).isEqualTo(failureCode));
        assertThat(policyCalls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void perInspectorFailOpenContinuesToLaterInspectors(boolean throwsException) {
        AtomicInteger laterInspectorCalls = new AtomicInteger();
        ContentInspector optionalInspector = inspector("optional", ignoredRequest -> {
            if (throwsException) {
                throw new InspectionException(InspectionFailureCode.TIMEOUT);
            }
            return InspectionResult.failed(InspectionFailureCode.TIMEOUT);
        });
        ContentInspector requiredInspector = inspector("required", ignoredRequest -> {
            laterInspectorCalls.incrementAndGet();
            return completedWithoutFindings();
        });
        InspectionService service = new InspectionService(
                List.of(optionalInspector, requiredInspector), InspectionPolicy.blockFindings(),
                InspectionFailurePolicy.FAIL_CLOSED,
                Map.of("optional", InspectionFailurePolicy.FAIL_OPEN));

        InspectionReport report = service.inspect(request());

        assertThat(report.allowedAfterFailure()).isTrue();
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("optional", "required");
        assertThat(report.outcomes().get(0).result())
                .isEqualTo(InspectionResult.failed(InspectionFailureCode.TIMEOUT));
        assertThat(laterInspectorCalls).hasValue(1);
    }

    @Test
    void perInspectorFailClosedOverridesCommonFailOpen() {
        AtomicInteger laterInspectorCalls = new AtomicInteger();
        ContentInspector requiredInspector = inspector("required", ignoredRequest ->
                InspectionResult.failed(InspectionFailureCode.TRANSPORT_ERROR));
        ContentInspector laterInspector = inspector("later", ignoredRequest -> {
            laterInspectorCalls.incrementAndGet();
            return completedWithoutFindings();
        });
        InspectionService service = new InspectionService(
                List.of(requiredInspector, laterInspector), InspectionPolicy.blockFindings(),
                InspectionFailurePolicy.FAIL_OPEN,
                Map.of("required", InspectionFailurePolicy.FAIL_CLOSED));

        InspectionReport report = service.inspect(request());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("required");
        assertThat(laterInspectorCalls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(InspectionFailurePolicy.class)
    void inspectorsWithoutOverridesUseCommonFailurePolicy(InspectionFailurePolicy commonFailurePolicy) {
        InspectionService service = new InspectionService(
                List.of(
                        inspector("optional", ignoredRequest ->
                                InspectionResult.failed(InspectionFailureCode.TIMEOUT)),
                        inspector("other", ignoredRequest ->
                                InspectionResult.failed(InspectionFailureCode.TRANSPORT_ERROR))),
                InspectionPolicy.blockFindings(), commonFailurePolicy,
                Map.of("optional", InspectionFailurePolicy.FAIL_OPEN));

        InspectionReport report = service.inspect(request());

        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("optional", "other");
        if (commonFailurePolicy == InspectionFailurePolicy.FAIL_CLOSED) {
            assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        } else {
            assertThat(report.allowedAfterFailure()).isTrue();
        }
    }

    @Test
    void perInspectorFailOpenRetainsFindingsAndCannotOverrideContentBlock() {
        InspectionFinding finding = new InspectionFinding(
                "s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult failedResult = InspectionResult.failed(
                InspectionFailureCode.TIMEOUT, Set.of("s1"), List.of(finding));
        AtomicInteger laterInspectorCalls = new AtomicInteger();
        ContentInspector laterInspector = inspector("later", ignoredRequest -> {
            laterInspectorCalls.incrementAndGet();
            return completedWithoutFindings();
        });

        InspectionService service = new InspectionService(
                List.of(inspector("optional", ignoredRequest -> failedResult), laterInspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_CLOSED,
                Map.of("optional", InspectionFailurePolicy.FAIL_OPEN));

        InspectionReport report = service.inspect(request());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes()).hasSize(1);
        assertThat(report.outcomes().get(0).result()).isEqualTo(failedResult);
        assertThat(laterInspectorCalls).hasValue(0);
    }

    @Test
    void failurePolicyOverridesAreFixedAtConstruction() {
        Map<String, InspectionFailurePolicy> overrides = new HashMap<>();
        overrides.put("optional", InspectionFailurePolicy.FAIL_OPEN);
        InspectionService service = new InspectionService(
                List.of(inspector("optional", ignoredRequest ->
                        InspectionResult.failed(InspectionFailureCode.TIMEOUT))),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_CLOSED, overrides);

        overrides.put("optional", InspectionFailurePolicy.FAIL_CLOSED);

        InspectionReport report = service.inspect(request());

        assertThat(report.allowedAfterFailure()).isTrue();
    }

    @Test
    void failurePolicyOverridesMustReferenceConfiguredInspectors() {
        List<ContentInspector> configuredInspectors =
                List.of(inspector("configured", ignoredRequest -> completedWithoutFindings()));
        Map<String, InspectionFailurePolicy> overrides = Map.of("unknown", InspectionFailurePolicy.FAIL_OPEN);

        assertThatThrownBy(() -> new InspectionService(configuredInspectors,
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_CLOSED, overrides))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failOpenOverrideDoesNotGiveLaterInspectorsANewTimeBudget() {
        AtomicInteger inspectorCalls = new AtomicInteger();
        ContentInspector optionalInspector = inspector("optional", ignoredRequest -> {
            inspectorCalls.incrementAndGet();
            return completedWithoutFindings();
        });
        ContentInspector requiredInspector = inspector("required", ignoredRequest -> {
            inspectorCalls.incrementAndGet();
            return completedWithoutFindings();
        });
        InspectionService service = new InspectionService(
                List.of(optionalInspector, requiredInspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_CLOSED,
                Map.of("optional", InspectionFailurePolicy.FAIL_OPEN));
        InspectionRequest expiredRequest = new InspectionRequest(request().segments(),
                new InspectionLimits(1, 100, Duration.ofNanos(1)));

        InspectionReport report = service.inspect(expiredRequest);

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("optional", "required");
        assertThat(report.outcomes()).allSatisfy(outcome ->
                assertThat(outcome.result()).isEqualTo(InspectionResult.failed(InspectionFailureCode.TIMEOUT)));
        assertThat(inspectorCalls).hasValue(0);
    }

    @Test
    void contentBlockStopsLaterInspectors() {
        InspectionFinding finding =
                new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult completedResult = InspectionResult.completed(Set.of("s1"), List.of(finding));
        ContentInspector blockingInspector = inspector("one", ignoredRequest -> completedResult);
        AtomicInteger laterInspectorCalls = new AtomicInteger();
        ContentInspector laterInspector = inspector("two", ignoredRequest -> {
            laterInspectorCalls.incrementAndGet();
            return completedWithoutFindings();
        });
        InspectionService service = new InspectionService(List.of(blockingInspector, laterInspector));

        InspectionReport report = service.inspect(request());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::result)
                .containsExactly(completedResult);
        assertThat(laterInspectorCalls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = ContentSegment.PrivacyProcessingStatus.class, names = {"UNKNOWN", "UNPROCESSED"})
    void disclosureIsPreflightedForAllInspectorsAndCannotFailOpen(ContentSegment.PrivacyProcessingStatus status) {
        AtomicInteger inspectorCalls = new AtomicInteger();
        ContentInspector remoteInspector = new ContentInspector() {
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
                inspectorCalls.incrementAndGet();
                return completedWithoutFindings();
            }
        };
        ContentInspector firstInspector = inspector("first", ignoredRequest -> {
            inspectorCalls.incrementAndGet();
            return completedWithoutFindings();
        });
        InspectionService service = new InspectionService(
                List.of(firstInspector, remoteInspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
        InspectionRequest mixedRequest = new InspectionRequest(List.of(
                new ContentSegment("s1", ContentSegment.Role.USER,
                        ContentSegment.PrivacyProcessingStatus.PROCESSED, "processed text"),
                new ContentSegment("s2", ContentSegment.Role.TOOL, status, "other text")),
                InspectionLimits.defaults());

        assertThatThrownBy(() -> service.inspect(mixedRequest))
                .isInstanceOf(InspectionException.class)
                .hasMessageContaining("DISCLOSURE_DENIED");
        assertThat(inspectorCalls).hasValue(0);
    }

    @Test
    void interruptionIsNeverAllowed() {
        InspectionService service = new InspectionService(
                List.of(inspector("one", ignoredRequest -> completedWithoutFindings())),
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
    void unclassifiedInspectorExceptionsAreSanitizedAndCannotFailOpen(InspectionFailurePolicy failurePolicy) {
        ContentInspector failingInspector = inspector("one", ignoredRequest -> {
            throw new IllegalStateException("secret customer@example.com");
        });
        InspectionService service = new InspectionService(
                List.of(failingInspector), InspectionPolicy.blockFindings(), failurePolicy);

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
        ContentInspector failingInspector = inspector("one", ignoredRequest -> {
            throw new AssertionError("fatal");
        });
        InspectionService service = new InspectionService(List.of(failingInspector));

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
            InspectionPolicy allowingPolicy = (inspectorId, findings) -> {
                policyCalls.incrementAndGet();
                return InspectionDecision.ALLOW;
            };
            InspectionService service = new InspectionService(
                    List.of(inspector("one", ignoredRequest -> result)), allowingPolicy, failurePolicy);

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
    void invalidAssociationsNeverDowngradeHardFailures(InspectionFailureCode failureCode) {
        for (InspectionResult result : List.of(
                InspectionResult.failed(failureCode, Set.of("unknown"), List.of()),
                InspectionResult.failed(failureCode, Set.of(), List.of(
                        new InspectionFinding("unknown", InspectionFinding.Category.PROMPT_ATTACK, "x", null))))) {
            InspectionService service = new InspectionService(List.of(inspector("one", ignoredRequest -> result)),
                    InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
            assertThatThrownBy(() -> service.inspect(request()))
                    .isInstanceOfSatisfying(InspectionException.class, ex -> {
                        assertThat(ex.failureCode()).isEqualTo(failureCode);
                        InspectionReport report = ex.report().orElseThrow();
                        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                        assertThat(report.outcomes().get(0).result().failureCode()).isEqualTo(failureCode);
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
        AtomicInteger laterInspectorCalls = new AtomicInteger();
        ContentInspector laterInspector = inspector("third", ignoredRequest -> {
            laterInspectorCalls.incrementAndGet();
            return completedWithoutFindings();
        });
        InspectionService service = new InspectionService(List.of(
                inspector("first", ignoredRequest -> completedWithoutFindings()),
                inspector("second", ignoredRequest -> partial),
                laterInspector),
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
        assertThat(laterInspectorCalls).hasValue(0);
    }

    @Test
    void emptyOrDuplicateInspectorConfigurationsAreRejected() {
        List<ContentInspector> duplicateInspectors = List.of(
                inspector("same", ignoredRequest -> completedWithoutFindings()),
                inspector("same", ignoredRequest -> completedWithoutFindings()));

        assertThatThrownBy(() -> new InspectionService(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InspectionService(duplicateInspectors))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredDeadlinePreservesReportedFailureAndPreventsLaterInspection(boolean throwsException) {
        InspectionFinding finding = new InspectionFinding(
                "s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult failedResult = InspectionResult.failed(
                InspectionFailureCode.MODEL_ERROR, Set.of("s1"), List.of(finding));
        AtomicInteger laterInspectorCalls = new AtomicInteger();
        ContentInspector firstInspector = inspector("first", request -> {
            waitUntilDeadlineExpires(request);
            if (throwsException) {
                throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
            }
            return failedResult;
        });
        ContentInspector laterInspector = inspector("later", ignoredRequest -> {
            laterInspectorCalls.incrementAndGet();
            return completedWithoutFindings();
        });

        InspectionService service = new InspectionService(
                List.of(firstInspector, laterInspector),
                (inspectorId, findings) -> InspectionDecision.ALLOW, InspectionFailurePolicy.FAIL_OPEN);
        InspectionRequest boundedRequest = new InspectionRequest(request().segments(),
                new InspectionLimits(1, 100, Duration.ofMillis(500)));

        InspectionReport report = service.inspect(boundedRequest);

        assertThat(report.allowedAfterFailure()).isTrue();
        assertThat(laterInspectorCalls).hasValue(0);
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("first", "later");
        InspectionResult firstResult = report.outcomes().get(0).result();
        assertThat(firstResult.failureCode()).isEqualTo(InspectionFailureCode.MODEL_ERROR);
        if (throwsException) {
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
    @EnumSource(value = InspectionFailureCode.class, names = {
            "TIMEOUT", "TRANSPORT_ERROR", "HTTP_ERROR", "MODEL_ERROR", "INVALID_RESPONSE", "INCOMPLETE"
    })
    void explicitlyClassifiedOperationalFailuresCanFailOpen(InspectionFailureCode failureCode) {
        ContentInspector classifiedInspector = inspector("classified", ignoredRequest -> {
            throw new InspectionException(failureCode);
        });
        InspectionService service = new InspectionService(List.of(classifiedInspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        InspectionReport report = service.inspect(request());

        assertThat(report.allowedAfterFailure()).isTrue();
        assertThat(report.outcomes().get(0).result().failureCode()).isEqualTo(failureCode);
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED, false", "COMPLETED, true", "FAILED, false", "FAILED, true"})
    void interruptionPreservesInspectorResultAndCannotFailOpen(
            InspectionResult.Status status, boolean interruptInPolicy) {
        InspectionFinding finding = new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult inspectorResult;
        if (status == InspectionResult.Status.COMPLETED) {
            inspectorResult = InspectionResult.completed(Set.of("s1"), List.of(finding));
        } else {
            inspectorResult = InspectionResult.failed(InspectionFailureCode.MODEL_ERROR, Set.of("s1"), List.of(finding));
        }
        ContentInspector inspector = inspector("one", ignoredRequest -> {
            if (!interruptInPolicy) {
                Thread.currentThread().interrupt();
            }
            return inspectorResult;
        });
        AtomicInteger policyCalls = new AtomicInteger();
        InspectionPolicy policy = (inspectorId, findings) -> {
            policyCalls.incrementAndGet();
            if (interruptInPolicy) {
                Thread.currentThread().interrupt();
            }
            return InspectionDecision.ALLOW;
        };
        AtomicInteger laterInspectorCalls = new AtomicInteger();
        ContentInspector laterInspector = inspector("later", ignoredRequest -> {
            laterInspectorCalls.incrementAndGet();
            return completedWithoutFindings();
        });
        InspectionService service = new InspectionService(
                List.of(inspector, laterInspector), policy, InspectionFailurePolicy.FAIL_OPEN);

        try {
            assertThatThrownBy(() -> service.inspect(request()))
                    .isInstanceOfSatisfying(InspectionException.class, ex -> {
                        assertThat(ex.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);
                        InspectionReport report = ex.report().orElseThrow();
                        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                        assertThat(report.outcomes()).hasSize(1);
                        assertThat(report.outcomes().get(0).result()).isEqualTo(inspectorResult);
                    });
            assertThat(laterInspectorCalls).hasValue(0);
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
            "ALLOW, false", "BLOCK, false",
            "ALLOW, true", "BLOCK, true"
    })
    void completedResultsSurviveDeadlineExpiryDuringInspectorOrPolicy(
            InspectionDecision contentDecision, boolean expireInPolicy) {
        InspectionFinding finding = new InspectionFinding("s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", null);
        InspectionResult inspectorResult = InspectionResult.completed(Set.of("s1"), List.of(finding));
        InspectionRequest boundedRequest = new InspectionRequest(request().segments(),
                new InspectionLimits(1, 100, Duration.ofMillis(100)));
        InspectionPolicy policy = (inspectorId, findings) -> {
            assertThat(findings).containsExactly(finding);
            if (expireInPolicy) {
                waitUntilDeadlineExpires(boundedRequest);
            }
            return contentDecision;
        };
        ContentInspector inspector = inspector("one", request -> {
            if (!expireInPolicy) {
                waitUntilDeadlineExpires(request);
            }
            return inspectorResult;
        });
        InspectionService service = new InspectionService(
                List.of(inspector), policy, InspectionFailurePolicy.FAIL_OPEN);

        InspectionReport report = service.inspect(boundedRequest);

        assertThat(report.decision()).isEqualTo(contentDecision);
        assertThat(report.outcomes().get(0).result()).isEqualTo(inspectorResult);
        assertThat(report.allowedAfterFailure()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"FAIL_CLOSED, false", "FAIL_OPEN, false", "FAIL_CLOSED, true", "FAIL_OPEN, true"})
    void deadlineExpiryPreventsLaterInspectionAndAppliesItsOwnFailurePolicy(
            InspectionFailurePolicy laterFailurePolicy, boolean expireInPolicy) {
        InspectionResult inspectorResult = completedWithoutFindings();
        AtomicInteger laterInspectorCalls = new AtomicInteger();
        InspectionRequest boundedRequest = new InspectionRequest(request().segments(),
                new InspectionLimits(1, 100, Duration.ofMillis(100)));
        ContentInspector firstInspector = inspector("first", request -> {
            if (!expireInPolicy) {
                waitUntilDeadlineExpires(request);
            }
            return inspectorResult;
        });
        ContentInspector laterInspector = inspector("later", ignoredRequest -> {
            laterInspectorCalls.incrementAndGet();
            return completedWithoutFindings();
        });
        InspectionPolicy policy = (inspectorId, findings) -> {
            if (expireInPolicy && inspectorId.equals("first")) {
                waitUntilDeadlineExpires(boundedRequest);
            }
            return InspectionDecision.ALLOW;
        };
        InspectionService service = new InspectionService(List.of(firstInspector, laterInspector), policy,
                InspectionFailurePolicy.FAIL_CLOSED, Map.of("later", laterFailurePolicy));

        InspectionReport report = service.inspect(boundedRequest);

        assertThat(laterInspectorCalls).hasValue(0);
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("first", "later");
        assertThat(report.outcomes().get(0).result()).isEqualTo(inspectorResult);
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
    void cancellationPreservesInspectorFailureInReport(InspectionFailureCode failureCode) {
        ContentInspector inspector = inspector("one", ignoredRequest -> {
            Thread.currentThread().interrupt();
            if (failureCode == InspectionFailureCode.INVALID_RESULT) {
                throw new IllegalStateException("private raw text");
            }
            throw new InspectionException(failureCode);
        });
        InspectionService service = new InspectionService(List.of(inspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        try {
            assertThatThrownBy(() -> service.inspect(request()))
                    .isInstanceOfSatisfying(InspectionException.class, ex -> {
                        assertThat(ex.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);
                        InspectionReport report = ex.report().orElseThrow();
                        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
                        assertThat(report.outcomes()).hasSize(1);
                        assertThat(report.outcomes().get(0).result().failureCode()).isEqualTo(failureCode);
                    })
                    .hasNoCause()
                    .hasMessageNotContaining("private raw text");
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
