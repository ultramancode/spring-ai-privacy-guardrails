package io.github.ultramancode.springai.privacy.core;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyValueTreeLimitsTest {

    @Test
    void valueTreeUsesTheConfiguredDepthLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxDepth(2).build();
        PrivacyService service = new PrivacyService(
                List.of((text, options, processingLimits) -> List.of()), PiiAnalysisOptions.defaults(), limits);
        Object accepted = nestedLists(2);
        Object rejected = nestedLists(3);

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenizeValueTree(session.handle(), accepted)).isEqualTo(accepted);
            assertThat(service.detokenizeValueTree(session.handle(), accepted)).isEqualTo(accepted);
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), rejected),
                    PrivacyPhase.TOKENIZATION
            );
        }
    }

    @Test
    void valueTreeCountsTheRootAndElementsAgainstTheNodeLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxValueTreeNodes(3).build();
        PrivacyService service = new PrivacyService(
                List.of((text, options, processingLimits) -> List.of()), PiiAnalysisOptions.defaults(), limits);
        List<Object> accepted = Collections.nCopies(2, null);
        List<Object> rejected = Collections.nCopies(3, null);

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenizeValueTree(session.handle(), accepted)).isEqualTo(accepted);
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), rejected),
                    PrivacyPhase.TOKENIZATION
            );
        }
    }

    @Test
    void scalarBatchRejectsOversizedCountBeforeAllocatingStorage() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());
        List<String> oversized = Collections.nCopies(Integer.MAX_VALUE, "x");

        try (PrivacySession session = service.openSession()) {
            assertValueTreeLimitFailure(
                    () -> service.tokenizeScalars(session.handle(), oversized),
                    PrivacyPhase.ANALYSIS
            );
        }
    }

    @Test
    void completedValueTreeTextStillEnforcesTheAnalysisInputLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxTextCharacters(5).build();
        PrivacyService service = new PrivacyService(
                List.of((text, options, processingLimits) -> List.of(new PiiSpan("PERSON", 0, 5, 0.95))),
                PiiAnalysisOptions.defaults(), limits);

        try (PrivacySession session = service.openSession()) {
            String protectedText = service.tokenize(session.handle(), "Alice");
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), List.of(protectedText)),
                    PrivacyPhase.ANALYSIS
            );
        }
    }

    @Test
    void completedValueTreeTextStillEnforcesTheCumulativeOutputLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxOutputCharacters(6).build();
        PrivacyService service = new PrivacyService(
                List.of((text, options, processingLimits) -> List.of()), PiiAnalysisOptions.defaults(), limits);

        try (PrivacySession session = service.openSession()) {
            String protectedText = service.tokenize(session.handle(), "safe");
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), List.of(protectedText, protectedText)),
                    PrivacyPhase.TOKENIZATION
            );
        }
    }

    @Test
    void valueTreeCharacterLimitIncludesAllStringValues() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxValueTreeCharacters(5).build();
        PrivacyService service = new PrivacyService(
                List.of((text, options, processingLimits) -> List.of()), PiiAnalysisOptions.defaults(), limits);
        String singleValue = "aaaaa";
        List<String> maximumAggregate = List.of("aa", "aaa");
        List<String> oversizedAggregate = List.of("aa", "aaaa");

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenizeValueTree(session.handle(), singleValue))
                    .isEqualTo(singleValue);
            assertThat(service.tokenizeValueTree(session.handle(), maximumAggregate))
                    .isEqualTo(maximumAggregate);
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), oversizedAggregate),
                    PrivacyPhase.TOKENIZATION
            );
        }
    }

    @Test
    void numericTokenizationEnforcesTheCharacterLimitAcrossApis() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxValueTreeCharacters(5).build();
        PrivacyService service = new PrivacyService(
                List.of((text, options, processingLimits) -> List.of()), PiiAnalysisOptions.defaults(), limits);
        BigInteger accepted = new BigInteger("11111");
        BigInteger rejected = new BigInteger("111111");

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenizeValueTree(session.handle(), accepted)).isEqualTo(accepted);
            assertThat(service.tokenizeScalar(session.handle(), accepted, List.of())).isEqualTo(accepted);
            assertThat(service.tokenizeScalars(session.handle(), List.of(accepted)))
                    .isEqualTo(List.of(accepted));
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), rejected),
                    PrivacyPhase.TOKENIZATION
            );
            assertValueTreeLimitFailure(
                    () -> service.tokenizeScalar(session.handle(), rejected, List.of()),
                    PrivacyPhase.ANALYSIS
            );
            assertValueTreeLimitFailure(
                    () -> service.tokenizeScalars(session.handle(), List.of(rejected)),
                    PrivacyPhase.ANALYSIS
            );
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, Integer.MAX_VALUE})
    void numericTokenizationRejectsDecimalExpansionBeforeAnalysis(int scale) {
        AtomicInteger calls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options, processingLimits) -> {
            calls.incrementAndGet();
            return List.of();
        };
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder()
                .maxTextCharacters(32)
                .maxValueTreeCharacters(32)
                .build();
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);
        BigDecimal oversized = new BigDecimal(BigInteger.ONE, scale);

        try (PrivacySession session = service.openSession()) {
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), oversized), PrivacyPhase.ANALYSIS);
            assertValueTreeLimitFailure(
                    () -> service.tokenizeScalar(session.handle(), oversized, List.of()), PrivacyPhase.ANALYSIS);
            assertValueTreeLimitFailure(
                    () -> service.tokenizeScalars(session.handle(), List.of(oversized)), PrivacyPhase.ANALYSIS);
            assertThat(calls).hasValue(0);
        }
    }

    @Test
    void valueTreeDetokenizationEnforcesTheCumulativeOutputLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxOutputCharacters(200).build();
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), limits);
        String original = "x".repeat(100);

        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(
                    session.handle(),
                    original,
                    List.of(new PiiSpan("SECRET", 0, original.length(), 1.0))
            );
            List<String> accepted = Collections.nCopies(2, token);
            List<String> rejected = Collections.nCopies(3, token);

            assertThat(service.detokenizeValueTree(session.handle(), accepted))
                    .isEqualTo(Collections.nCopies(2, original));
            assertValueTreeLimitFailure(
                    () -> service.detokenizeValueTree(session.handle(), rejected),
                    PrivacyPhase.DETOKENIZATION
            );
        }
    }

    @Test
    void valueTreeSpanLimitAppliesAcrossValues() {
        PiiAnalyzer analyzer = (text, options, processingLimits) -> List.of(new PiiSpan("PII", 0, 1, 1.0));
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(2).build();
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);

        try (PrivacySession session = service.openSession()) {
            Object protectedValues = service.tokenizeValueTree(session.handle(), List.of("a", "b"));
            assertThat(service.detokenizeValueTree(session.handle(), protectedValues)).isEqualTo(List.of("a", "b"));
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), List.of("a", "b", "c")),
                    PrivacyPhase.TOKENIZATION
            );
        }
    }

    private static Object nestedLists(int depth) {
        Object value = null;
        for (int index = 0; index < depth; index++) {
            value = Collections.singletonList(value);
        }
        return value;
    }

    private static void assertValueTreeLimitFailure(
            ThrowingCallable operation,
            PrivacyPhase phase
    ) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(phase);
                });
    }
}
