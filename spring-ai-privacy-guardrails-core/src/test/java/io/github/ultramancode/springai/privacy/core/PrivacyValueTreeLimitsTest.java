package io.github.ultramancode.springai.privacy.core;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

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
    void valueTreeNumbersRespectTheConfiguredCharacterLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxValueTreeCharacters(5).build();
        PrivacyService service = new PrivacyService(
                List.of((text, options, processingLimits) -> List.of()), PiiAnalysisOptions.defaults(), limits);
        BigInteger accepted = new BigInteger("11111");
        BigInteger rejected = new BigInteger("111111");

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenizeValueTree(session.handle(), accepted)).isEqualTo(accepted);
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), rejected),
                    PrivacyPhase.TOKENIZATION
            );
        }
    }

    @Test
    void numericScalarTokenizationRejectsOversizedNumbers() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxValueTreeCharacters(5).build();
        PrivacyService service = new PrivacyService(
                List.of((text, options, processingLimits) -> List.of()), PiiAnalysisOptions.defaults(), limits);
        BigInteger oversized = new BigInteger("111111");

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> service.tokenizeScalar(
                    session.handle(), oversized, List.of()))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                    });
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
