package io.github.ultramancode.springai.privacy.core;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.AbstractList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyProcessingLimitsTest {

    @Test
    void builderUsesDefaultsAndValidatesOverrides() {
        assertThat(PrivacyProcessingLimits.builder().build()).isEqualTo(PrivacyProcessingLimits.defaults());
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder()
                .maxTextCharacters(10)
                .maxOutputCharacters(20)
                .maxValueTreeCharacters(30)
                .maxValueTreeNodes(4)
                .maxDepth(5)
                .maxAnalysisSegments(6)
                .maxResultSpans(7)
                .build();

        assertThat(limits).isEqualTo(new PrivacyProcessingLimits(10, 20, 30, 4, 5, 6, 7));
        assertThatThrownBy(() -> PrivacyProcessingLimits.builder().maxDepth(0).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxDepth must be positive");
    }

    @Test
    void configuredTextLimitAppliesBeforeAnalyzerInvocation() {
        int textLimit = 3;
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxTextCharacters(textLimit).build();
        AtomicInteger analyzerCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options, processingLimits) -> {
            assertThat(processingLimits).isEqualTo(limits);
            analyzerCalls.incrementAndGet();
            return List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);

        assertThat(service.analyze("x".repeat(textLimit))).isEmpty();
        assertThat(analyzerCalls).hasValue(1);

        assertThatThrownBy(() -> service.analyze(
                "x".repeat(textLimit + 1)
        )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
            assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
            assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
        });
        assertThat(analyzerCalls).hasValue(1);
    }

    @Test
    void shrinkingReplacementsSucceedWhenTheFinalOutputFitsTheLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxOutputCharacters(120).build();
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), limits);
        String input = "a".repeat(400);
        List<PiiSpan> spans = List.of(
                new PiiSpan("PII", 0, 200, 1.0),
                new PiiSpan("PII", 200, 400, 1.0));

        assertThat(service.redact(input, spans)).isEqualTo("[REDACTED_PII]".repeat(2));
        try (PrivacySession session = service.openSession()) {
            String tokenized = service.tokenize(session.handle(), input, spans);
            assertThat(tokenized).hasSizeLessThanOrEqualTo(120);
            assertThat(OpaquePiiTokenFormat.patternForEntityType("PII")
                    .matcher(tokenized).results().count()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tokenizationEnforcesTheOutputLimitForNewAndReusedTokens(boolean numericScalar) {
        int singleDigitTokenLength = OpaquePiiTokenFormat.format("PERSON", "0".repeat(32), 1).length();
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder()
                .maxOutputCharacters(singleDigitTokenLength)
                .build();
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), limits);
        List<PiiSpan> spans = List.of(new PiiSpan("PERSON", 0, 1, 1.0));

        try (PrivacySession session = service.openSession()) {
            Object firstToken = null;
            for (int index = 1; index <= 10; index++) {
                Object original = numericScalar ? index : Integer.toString(index);
                List<PiiSpan> originalSpans = List.of(
                        new PiiSpan("PERSON", 0, original.toString().length(), 1.0));
                if (index == 10) {
                    assertPayloadLimitExceeded(
                            () -> service.tokenizeScalar(session.handle(), original, originalSpans),
                            PrivacyPhase.TOKENIZATION);
                } else {
                    Object token = service.tokenizeScalar(session.handle(), original, originalSpans);
                    assertThat((String) token).hasSize(singleDigitTokenLength);
                    if (index == 1) {
                        firstToken = token;
                    }
                }
            }
            Object firstOriginal = numericScalar ? 1 : "1";
            assertThat(service.tokenizeScalar(session.handle(), firstOriginal, spans)).isEqualTo(firstToken);
        }
    }

    @Test
    void outputLimitAppliesToUnchangedTextAndWhitespace() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxOutputCharacters(80).build();
        PrivacyService service = new PrivacyService(
                List.of((text, options, processingLimits) -> List.of()), PiiAnalysisOptions.defaults(), limits);

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenize(session.handle(), "x".repeat(80))).hasSize(80);
            assertThat(service.redact(" ".repeat(80))).hasSize(80);
            for (String input : List.of("x".repeat(81), " ".repeat(81))) {
                assertPayloadLimitExceeded(
                        () -> service.tokenize(session.handle(), input), PrivacyPhase.TOKENIZATION);
                assertPayloadLimitExceeded(() -> service.redact(input), PrivacyPhase.REDACTION);
                assertPayloadLimitExceeded(
                        () -> service.redact(session.handle(), input), PrivacyPhase.REDACTION);
                assertPayloadLimitExceeded(
                        () -> service.detokenize(session.handle(), input), PrivacyPhase.DETOKENIZATION);
            }
            service.tokenize(session.handle(), "x", List.of(new PiiSpan("SECRET", 0, 1, 1.0)));
            assertPayloadLimitExceeded(
                    () -> service.detokenize(session.handle(), "y".repeat(81)), PrivacyPhase.DETOKENIZATION);
        }
    }

    @Test
    void callerSuppliedSpansRejectOversizedSourceText() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxTextCharacters(3).build();
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), limits);
        int oversizedLength = limits.maxTextCharacters() + 1;
        // A range failure here would show that span resolution ran before the text limit check.
        PiiSpan outOfRangeSpan = new PiiSpan("SECRET", oversizedLength, oversizedLength + 1, 1.0);

        assertThatThrownBy(() -> service.redact(
                "x".repeat(oversizedLength),
                List.of(outOfRangeSpan)
        )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
            assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
            assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
        });
    }

    @Test
    void automaticTextOperationsRejectOversizedWhitespace() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxTextCharacters(3).build();
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), limits);
        String oversizedWhitespace = " ".repeat(limits.maxTextCharacters() + 1);

        try (PrivacySession session = service.openSession()) {
            assertAnalysisPayloadLimitExceeded(() -> service.redact(oversizedWhitespace));
            assertAnalysisPayloadLimitExceeded(() -> service.redact(session.handle(), oversizedWhitespace));
            assertAnalysisPayloadLimitExceeded(() -> service.containsPii(session.handle(), oversizedWhitespace));
        }
    }

    @Test
    void textReplacementsRejectOutputPastTheConfiguredLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxOutputCharacters(20).build();
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), limits);
        List<PiiSpan> spans = List.of(
                new PiiSpan("PERSON", 0, 1, 1.0),
                new PiiSpan("PERSON", 1, 2, 1.0));

        assertPayloadLimitExceeded(() -> service.redact("ab", spans), PrivacyPhase.REDACTION);
        try (PrivacySession session = service.openSession()) {
            assertPayloadLimitExceeded(
                    () -> service.tokenize(session.handle(), "ab", spans), PrivacyPhase.TOKENIZATION);
        }
    }

    @Test
    void detokenizationUsesTheConfiguredOutputLimitForRepeatedExpansion() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxOutputCharacters(200).build();
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), limits);
        String original = "x".repeat(100);

        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(
                    session.handle(),
                    original,
                    List.of(new PiiSpan("SECRET", 0, original.length(), 1.0))
            );
            assertThat(service.detokenize(session.handle(), token.repeat(2)))
                    .isEqualTo(original.repeat(2));

            assertThatThrownBy(() -> service.detokenize(session.handle(), token.repeat(3)))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.DETOKENIZATION);
                    });
        }
    }

    @Test
    void coreRejectsOversizedAnalyzerResultsBeforeIteratingThem() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(1).build();
        PiiAnalyzer analyzer = (text, options, processingLimits) -> oversizedSpanList(limits.maxResultSpans());
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);

        assertThatThrownBy(() -> service.analyze("A"))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                });
    }

    @Test
    void defaultSegmentedAnalysisRejectsOversizedResultsBeforeCopying() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(1).build();
        PiiAnalyzer analyzer = (text, options, processingLimits) -> oversizedSpanList(limits.maxResultSpans());
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.defaults(),
                limits
        );

        assertThatThrownBy(() -> service.analyzeSegments(List.of("A")))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                });
    }

    @Test
    void analysisRejectsCombinedProviderResultsAboveTheSpanLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(2).build();
        PiiSpan span = new PiiSpan("PERSON", 0, 1, 1.0);
        PiiAnalyzer first = namedAnalyzer(
                "FIRST",
                (text, options, processingLimits) -> List.of(span)
        );
        PiiAnalyzer second = namedAnalyzer("SECOND", (text, options, processingLimits) -> new AbstractList<>() {
            @Override
            public PiiSpan get(int index) {
                throw new AssertionError("the provider overflow must be rejected before iteration");
            }

            @Override
            public int size() {
                return 2;
            }
        });
        PrivacyService service = new PrivacyService(
                List.of(first, second),
                PiiAnalysisOptions.defaults(),
                limits
        );

        assertThatThrownBy(() -> service.analyze("A"))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void segmentedAnalysisRejectsExcessSegmentsBeforeIteration(boolean sessionAware) {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder()
                .maxAnalysisSegments(2).maxValueTreeNodes(1).build();
        AtomicInteger batchCalls = new AtomicInteger();
        PiiAnalyzer analyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(
                    String text, PiiAnalysisOptions options, PrivacyProcessingLimits processingLimits) {
                assertThat(processingLimits).isEqualTo(limits);
                return List.of();
            }

            @Override
            public List<List<PiiSpan>> analyzeSegments(
                    List<String> texts, PiiAnalysisOptions options, PrivacyProcessingLimits processingLimits) {
                assertThat(processingLimits).isEqualTo(limits);
                batchCalls.incrementAndGet();
                return PiiAnalyzer.super.analyzeSegments(texts, options, processingLimits);
            }
        };
        List<String> excessiveSegments = new AbstractList<>() {
            @Override
            public String get(int index) {
                throw new AssertionError("segment limit must be checked before iteration");
            }

            @Override
            public int size() {
                return limits.maxAnalysisSegments() + 1;
            }
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);

        try (PrivacySession session = service.openSession()) {
            Function<List<String>, List<List<ResolvedPiiSpan>>> analyze = texts -> sessionAware
                    ? service.analyzeSegments(session.handle(), texts) : service.analyzeSegments(texts);
            assertThat(analyze.apply(List.of("a", "b")))
                    .containsExactly(List.of(), List.of());
            assertThat(batchCalls).hasValue(1);

            assertThatThrownBy(() -> analyze.apply(excessiveSegments))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                    });
            assertThat(batchCalls).hasValue(1);
        }
    }

    @Test
    void segmentedAnalysisUsesTheConfiguredAggregateInputLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxTextCharacters(3).build();
        AtomicInteger analyzerCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options, processingLimits) -> {
            analyzerCalls.incrementAndGet();
            return List.of();
        };
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.defaults(),
                limits
        );

        assertThat(service.analyzeSegments(List.of("a", "bc")))
                .containsExactly(List.of(), List.of());
        assertThat(analyzerCalls).hasValue(2);

        assertThatThrownBy(() -> service.analyzeSegments(List.of("ab", "cd")))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                });
        assertThat(analyzerCalls).hasValue(2);
    }

    @Test
    void segmentedAnalysisRejectsOversizedWhitespace() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxTextCharacters(3).build();
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), limits);
        String oversizedBlank = " ".repeat(limits.maxTextCharacters() + 1);

        assertThatThrownBy(() -> service.analyzeSegments(List.of(oversizedBlank)))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                });
    }

    @Test
    void segmentedAnalysisRejectsCombinedResultsAboveTheSpanLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(2).build();
        PiiSpan span = new PiiSpan("PERSON", 0, 1, 1.0);
        PiiAnalyzer analyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(
                    String text,
                    PiiAnalysisOptions options,
                    PrivacyProcessingLimits limits
            ) {
                return List.of();
            }

            @Override
            public List<List<PiiSpan>> analyzeSegments(
                    List<String> texts,
                    PiiAnalysisOptions options,
                    PrivacyProcessingLimits limits
            ) {
                return List.of(
                        List.of(span),
                        new AbstractList<>() {
                            @Override
                            public PiiSpan get(int index) {
                                throw new AssertionError(
                                        "segment overflow must be rejected before iteration"
                                );
                            }

                            @Override
                            public int size() {
                                return 2;
                            }
                        }
                );
            }
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);

        assertThatThrownBy(() -> service.analyzeSegments(List.of("A", "B")))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code())
                            .isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                });
    }

    @Test
    void configuredSpanLimitAppliesToRegexResultsAndSuppliedSpans() {
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule("CHARACTER", ".", 1.0, 0)
        ));
        PrivacyProcessingLimits acceptingLimits = PrivacyProcessingLimits.builder().maxResultSpans(2).build();
        PrivacyProcessingLimits rejectingLimits = PrivacyProcessingLimits.builder().maxResultSpans(1).build();
        List<PiiSpan> twoSpans = List.of(
                new PiiSpan("CHARACTER", 0, 1, 1.0),
                new PiiSpan("CHARACTER", 1, 2, 1.0)
        );

        assertThat(analyzer.analyze("ab", PiiAnalysisOptions.defaults(), acceptingLimits)).hasSize(2);
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), rejectingLimits);
        assertAnalysisPayloadLimitExceeded(() -> service.redact("ab", twoSpans));
        assertAnalysisPayloadLimitExceeded(() -> analyzer.analyze("ab", PiiAnalysisOptions.defaults(), rejectingLimits));
    }

    @Test
    void allowPartialStillRejectsCollectedOrReturnedSpanLimitFailures() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(1).build();
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        PiiAnalyzer collectingAnalyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule("CHARACTER", ".", 1.0, 0)));
        PiiAnalyzer returningAnalyzer = (text, options, processingLimits) -> List.of(
                new PiiSpan("PII", 0, 1, 1.0), new PiiSpan("PII", 1, 2, 1.0));
        PiiAnalyzer successfulAnalyzer = namedAnalyzer("EMPTY", (text, options, processingLimits) -> List.of());

        for (PiiAnalyzer analyzer : List.of(collectingAnalyzer, returningAnalyzer)) {
            PrivacyService service = new PrivacyService(
                    List.of(successfulAnalyzer, analyzer), PiiAnalysisOptions.defaults(),
                    EntityTypeRegistry.defaults(), policy, PiiAnalyzerFailureObserver.noop(), limits);
            assertAnalysisPayloadLimitExceeded(() -> service.analyze("ab"));
            assertAnalysisPayloadLimitExceeded(() -> service.analyzeSegments(List.of("ab")));
        }
    }

    @Test
    void allowPartialStillRejectsTheCombinedSpanLimitAcrossProviders() {
        PiiSpan span = new PiiSpan("PERSON", 0, 1, 1.0);
        PiiAnalyzer first = namedAnalyzer("FIRST", (text, options, processingLimits) -> List.of(span));
        PiiAnalyzer second = namedAnalyzer("SECOND", (text, options, processingLimits) -> List.of(span));
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(1).build();
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        PrivacyService service = new PrivacyService(
                List.of(first, second), PiiAnalysisOptions.defaults(),
                EntityTypeRegistry.defaults(), policy, PiiAnalyzerFailureObserver.noop(), limits);

        assertAnalysisPayloadLimitExceeded(() -> service.analyze("a"));
        assertAnalysisPayloadLimitExceeded(() -> service.analyzeSegments(List.of("a")));
    }

    @Test
    void payloadLimitFailuresAreSanitizedAndDoNotTriggerFallback() {
        AtomicInteger fallbackCalls = new AtomicInteger();
        PiiAnalyzer primary = namedAnalyzer("PRIMARY", (text, options, processingLimits) -> {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                    PrivacyPhase.ANALYSIS, "untrusted private value");
        });
        PiiAnalyzer fallback = namedAnalyzer("FALLBACK", (text, options, processingLimits) -> {
            fallbackCalls.incrementAndGet();
            return List.of();
        });
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY_WITH_FALLBACK)
                .primaryProvider("PRIMARY")
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        PrivacyService service = new PrivacyService(
                List.of(primary, fallback), PiiAnalysisOptions.defaults(),
                EntityTypeRegistry.defaults(), policy);

        assertThatThrownBy(() -> service.analyze("a"))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                })
                .hasMessageNotContaining("untrusted private value")
                .hasNoCause();
        assertAnalysisPayloadLimitExceeded(() -> service.analyzeSegments(List.of("a")));
        assertThat(fallbackCalls).hasValue(0);
    }

    private static List<PiiSpan> oversizedSpanList(int maxResultSpans) {
        return new AbstractList<>() {
            @Override
            public PiiSpan get(int index) {
                throw new AssertionError("oversized results must be rejected before iteration");
            }

            @Override
            public int size() {
                return maxResultSpans + 1;
            }
        };
    }

    private static void assertAnalysisPayloadLimitExceeded(ThrowingCallable operation) {
        assertPayloadLimitExceeded(operation, PrivacyPhase.ANALYSIS);
    }

    private static void assertPayloadLimitExceeded(ThrowingCallable operation, PrivacyPhase phase) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(phase);
                });
    }

    private static PiiAnalyzer namedAnalyzer(String providerId, PiiAnalyzer delegate) {
        return new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(
                    String text,
                    PiiAnalysisOptions options,
                    PrivacyProcessingLimits limits
            ) {
                return delegate.analyze(text, options, limits);
            }

            @Override
            public String providerId() {
                return providerId;
            }
        };
    }
}
