package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacySessionAnalysisReuseTest {

    @Test
    void completionSkipsTokenizeButDetailedAnalysisStillUsesRealSourceEvidence() {
        AtomicInteger calls = new AtomicInteger();
        PrivacyService service = new PrivacyService(List.of(personAnalyzer(calls)), PiiAnalysisOptions.defaults());
        try (PrivacySession session = service.openSession()) {
            String protectedText = service.tokenize(session.handle(), "Find Alice");
            assertThat(protectedText).doesNotContain("Alice");
            assertThat(service.tokenize(session.handle(), protectedText)).isEqualTo(protectedText);
            assertThat(calls).hasValue(1);

            PiiTokenizationResult detailed = service.analyzeAndTokenize(session.handle(), protectedText);
            assertThat(detailed.analysis().successfulProviders()).isNotEmpty();
            assertThat(detailed.tokenizedText()).isEqualTo(protectedText);
            assertThat(calls).hasValue(2);
            assertThat(service.analyzeAndTokenize(session.handle(), protectedText).analysis())
                    .isEqualTo(detailed.analysis());
            assertThat(calls).hasValue(2);
        }
    }

    @Test
    void suppliedSpansCannotPublishAnalysisOrCompletion() {
        AtomicInteger calls = new AtomicInteger();
        PrivacyService service = new PrivacyService(List.of(personAnalyzer(calls)), PiiAnalysisOptions.defaults());
        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenize(session.handle(), "Alice", List.of())).isEqualTo("Alice");
            assertThat(service.tokenize(session.handle(), "Alice"))
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void scalarBatchKeepsCompletionDecisionWhenAnotherThreadEvictsIt() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger protectedTextAnalyses = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options, limits) -> {
            if (text.startsWith("[[")) {
                protectedTextAnalyses.incrementAndGet();
            }
            if (text.equals("Bob")) {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("analysis wait timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("analysis interrupted", interrupted);
                }
            }
            return text.equals("Alice") || text.equals("Bob")
                    ? List.of(new PiiSpan("PERSON", 0, text.length(), 0.95)) : List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (PrivacySession session = service.openSession()) {
            String protectedAlice = service.tokenize(session.handle(), "Alice");
            Future<List<Object>> pending = executor.submit(() -> service.tokenizeScalars(
                    session.handle(), List.of(protectedAlice, "Bob")));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            for (int index = 0; index < 320; index++) {
                service.tokenize(session.handle(), "safe text " + index);
            }
            release.countDown();

            List<Object> result = pending.get(5, TimeUnit.SECONDS);
            assertThat(result.get(0)).isEqualTo(protectedAlice);
            assertThat(result.get(1)).isInstanceOfSatisfying(String.class,
                    text -> assertThat(text).matches(OpaquePiiTokenFormat.patternForEntityType("PERSON")));
            assertThat(service.detokenizeValueTree(session.handle(), result)).isEqualTo(List.of("Alice", "Bob"));
            assertThat(protectedTextAnalyses).hasValue(0);

            // Confirm eviction occurred: a later call must analyze the protected text again.
            assertThat(service.tokenize(session.handle(), protectedAlice)).isEqualTo(protectedAlice);
            assertThat(protectedTextAnalyses).hasValue(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void completedValueTreeTextStillRejectsInterruption() {
        AtomicInteger calls = new AtomicInteger();
        PrivacyService service = new PrivacyService(List.of(personAnalyzer(calls)), PiiAnalysisOptions.defaults());
        try (PrivacySession session = service.openSession()) {
            String protectedText = service.tokenize(session.handle(), "Alice");
            Thread.currentThread().interrupt();
            try {
                assertThatThrownBy(() -> service.tokenizeValueTree(session.handle(), List.of(protectedText)))
                        .isInstanceOfSatisfying(PrivacyGuardrailException.class,
                                failure -> assertThat(failure.code())
                                        .isEqualTo(PrivacyFailureCode.ANALYSIS_INTERRUPTED));
            } finally {
                Thread.interrupted();
            }
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void batchEvidenceLimitIncludesReusedResultsWithoutCachingFailedBatches() {
        AtomicInteger calls = new AtomicInteger();
        PiiAnalyzer first = (text, options, limits) -> {
            calls.incrementAndGet();
            return List.of(new PiiSpan("PERSON", 0, 5, 0.95));
        };
        PiiAnalyzer second = new PiiAnalyzer() {
            @Override
            public String providerId() {
                return "SECOND";
            }

            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options,
                                         PrivacyProcessingLimits limits) {
                return List.of(new PiiSpan("PERSON", 0, 5, 0.9));
            }
        };
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(3).build();
        PrivacyService service = new PrivacyService(List.of(first, second),
                PiiAnalysisOptions.defaults(), limits);
        try (PrivacySession session = service.openSession()) {
            assertThat(service.analyzeSegments(session.handle(), List.of("Alice"))).hasSize(1);
            assertThat(calls).hasValue(1);
            assertThatThrownBy(() -> service.analyzeSegments(session.handle(),
                    List.of("Alice", "Alice")))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED));
            assertThat(calls).hasValue(1);

            assertThatThrownBy(() -> service.analyzeSegments(session.handle(),
                    List.of("Alice", "Carol")))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED));
            assertThat(calls).hasValue(2);
            service.analyzeSegments(session.handle(), List.of("Carol"));
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void segmentLimitCountsReusedAndEmptyInputsBeforeAnalysis() {
        AtomicInteger calls = new AtomicInteger();
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxAnalysisSegments(1).build();
        PrivacyService service = new PrivacyService(List.of(personAnalyzer(calls)),
                PiiAnalysisOptions.defaults(), limits);
        List<List<String>> oversizedInputs = List.of(
                List.of("Alice", "Alice"), Arrays.asList(null, ""));

        try (PrivacySession session = service.openSession()) {
            service.analyzeSegments(session.handle(), List.of("Alice"));
            for (List<String> texts : oversizedInputs) {
                assertThatThrownBy(() -> service.analyzeSegments(session.handle(), texts))
                        .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                            assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                            assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                        });
            }
            assertThat(calls).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0000001", "1e7"})
    void numericTokenizationSharesPlainDecimalAnalysisAcrossApis(String literal) {
        BigDecimal number = new BigDecimal(literal);
        String plainText = number.toPlainString();
        AtomicInteger calls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options, limits) -> {
            calls.incrementAndGet();
            return text.equals(plainText)
                    ? List.of(new PiiSpan("PII", 0, text.length(), 0.95)) : List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        try (PrivacySession session = service.openSession()) {
            List<Object> protectedScalars = service.tokenizeScalars(
                    session.handle(), List.of(plainText, number));
            assertThat(protectedScalars).hasSize(2).allMatch(String.class::isInstance);
            assertThat(protectedScalars.get(0)).isNotEqualTo(protectedScalars.get(1));
            assertThat(service.tokenizeValueTree(session.handle(), number))
                    .isEqualTo(protectedScalars.get(1));
            assertThat(service.tokenizeScalar(session.handle(), number,
                    List.of(new PiiSpan("PII", 0, plainText.length(), 0.95))))
                    .isEqualTo(protectedScalars.get(1));
            assertThat(service.detokenizeValueTree(session.handle(), protectedScalars.get(1)))
                    .isEqualTo(number);
            assertThat(calls).hasValue(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void scalarBatchCountsRepeatedInputsBeforeDeduplicationAndReuse(boolean numeric) {
        AtomicInteger calls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options, processingLimits) -> {
            calls.incrementAndGet();
            return List.of();
        };
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxTextCharacters(8).build();
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);
        Object scalar = numeric ? new BigDecimal("1e3") : "1000";
        List<Object> accepted = List.of(scalar, scalar);
        List<Object> oversized = List.of(scalar, scalar, scalar);

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> service.tokenizeScalars(session.handle(), oversized))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED));
            assertThat(calls).hasValue(0);

            assertThat(service.tokenizeScalars(session.handle(), accepted)).isEqualTo(accepted);
            assertThat(calls).hasValue(1);

            assertThatThrownBy(() -> service.tokenizeScalars(session.handle(), oversized))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED));
            assertThat(calls).hasValue(1);
        }
    }

    @Test
    void partialFailureDoesNotPublishSourceOrCompletion() {
        AtomicInteger calls = new AtomicInteger();
        PiiAnalyzer failing = new PiiAnalyzer() {
            @Override
            public String providerId() {
                return "FAILS";
            }

            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options,
                                         PrivacyProcessingLimits limits) {
                throw new IllegalStateException("unavailable");
            }
        };
        PrivacyService service = new PrivacyService(
                List.of(personAnalyzer(calls), failing), PiiAnalysisOptions.defaults(),
                EntityTypeRegistry.defaults(),
                PiiResolutionPolicy.builder().failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL).build());
        try (PrivacySession session = service.openSession()) {
            String protectedText = service.tokenize(session.handle(), "Alice");
            assertThat(protectedText).doesNotContain("Alice");
            assertThat(service.tokenize(session.handle(), protectedText)).isEqualTo(protectedText);
            assertThat(calls).hasValue(2);
            service.tokenize(session.handle(), "Alice");
            assertThat(calls).hasValue(3);
        }
    }

    @Test
    void reuseIsConfinedToOneSessionAndEvictedSourcesAreAnalyzedAgain() {
        AtomicInteger calls = new AtomicInteger();
        PrivacyService service = new PrivacyService(List.of(personAnalyzer(calls)), PiiAnalysisOptions.defaults());
        try (PrivacySession session = service.openSession()) {
            String protectedText = service.tokenize(session.handle(), "Alice");
            for (int index = 0; index < 320; index++) {
                service.tokenize(session.handle(), "safe text " + index);
            }
            int beforeRepeat = calls.get();
            assertThat(service.analyzeAndTokenize(session.handle(), "Alice").tokenizedText())
                    .isEqualTo(protectedText);
            assertThat(service.detokenize(session.handle(), protectedText)).isEqualTo("Alice");
            assertThat(calls).hasValue(beforeRepeat + 1);
        }
        try (PrivacySession anotherSession = service.openSession()) {
            int before = calls.get();
            service.tokenize(anotherSession.handle(), "Alice");
            assertThat(calls).hasValue(before + 1);
        }
    }

    @Test
    void closeDuringRemoteAnalysisPreventsPublicationAndReturn() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        PiiAnalyzer analyzer = (text, options, limits) -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("analysis wait timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("analysis interrupted", interrupted);
            }
            return List.of(new PiiSpan("PERSON", 0, 5, 0.95));
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        PrivacySession session = service.openSession();
        try {
            Future<String> pending = executor.submit(() -> service.tokenize(session.handle(), "Alice"));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            session.close();
            release.countDown();
            assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(PrivacyGuardrailException.class)
                    .cause().isInstanceOfSatisfying(PrivacyGuardrailException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo(PrivacyFailureCode.CONTEXT_NOT_ACTIVE));
        } finally {
            release.countDown();
            session.close();
            executor.shutdownNow();
        }
    }

    private static PiiAnalyzer personAnalyzer(AtomicInteger calls) {
        return (text, options, limits) -> {
            calls.incrementAndGet();
            int start = text.indexOf("Alice");
            return start < 0 ? List.of()
                    : List.of(new PiiSpan("PERSON", start, start + 5, 0.95));
        };
    }
}
