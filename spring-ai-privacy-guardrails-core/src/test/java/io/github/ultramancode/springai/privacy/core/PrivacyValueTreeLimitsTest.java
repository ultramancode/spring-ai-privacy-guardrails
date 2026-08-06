package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyValueTreeLimitsTest {

    @Test
    void valueTreeAcceptsTheMaximumContainerDepth() {
        PrivacyService service = serviceWithNoopAnalyzer();
        Object input = nestedLists(PrivacyService.MAX_VALUE_TREE_DEPTH);

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenizeValueTree(session.handle(), input)).isEqualTo(input);
        }
    }

    @Test
    void valueTreeRejectsContainerDepthPastTheLimit() {
        PrivacyService service = serviceWithNoopAnalyzer();
        Object input = nestedLists(PrivacyService.MAX_VALUE_TREE_DEPTH + 1);

        try (PrivacySession session = service.openSession()) {
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), input),
                    PrivacyPhase.TOKENIZATION
            );
        }
    }

    @Test
    void valueTreeCountsTheRootAndElementsAgainstTheNodeLimit() {
        PrivacyService service = serviceWithNoopAnalyzer();
        List<Object> accepted = Collections.nCopies(
                PrivacyService.MAX_VALUE_TREE_NODES - 1,
                null
        );
        List<Object> rejected = Collections.nCopies(
                PrivacyService.MAX_VALUE_TREE_NODES,
                null
        );

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenizeValueTree(session.handle(), accepted)).isEqualTo(accepted);
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), rejected),
                    PrivacyPhase.TOKENIZATION
            );
        }
    }

    @Test
    void valueTreeEnforcesPerValueAndCumulativeInputLimits() {
        PrivacyService service = serviceWithNoopAnalyzer();
        String maximumValue = "a".repeat(PrivacyService.MAX_VALUE_TREE_STRING_CHARACTERS);
        List<String> maximumAggregate = Collections.nCopies(
                PrivacyService.MAX_VALUE_TREE_INPUT_CHARACTERS
                        / PrivacyService.MAX_VALUE_TREE_STRING_CHARACTERS,
                maximumValue
        );
        List<String> oversizedAggregate = new ArrayList<>(maximumAggregate);
        oversizedAggregate.add("x");

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenizeValueTree(session.handle(), maximumAggregate))
                    .isEqualTo(maximumAggregate);
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(
                            session.handle(),
                            "a".repeat(PrivacyService.MAX_VALUE_TREE_STRING_CHARACTERS + 1)
                    ),
                    PrivacyPhase.TOKENIZATION
            );
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), oversizedAggregate),
                    PrivacyPhase.TOKENIZATION
            );
        }
    }

    @Test
    void valueTreeEnforcesTheNumberRepresentationLimit() {
        PrivacyService service = serviceWithNoopAnalyzer();
        BigInteger maximumNumber = new BigInteger(
                "1".repeat(PrivacyService.MAX_VALUE_TREE_NUMBER_CHARACTERS)
        );
        BigInteger oversizedNumber = new BigInteger(
                "1".repeat(PrivacyService.MAX_VALUE_TREE_NUMBER_CHARACTERS + 1)
        );

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenizeValueTree(session.handle(), maximumNumber))
                    .isEqualTo(maximumNumber);
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), oversizedNumber),
                    PrivacyPhase.TOKENIZATION
            );
        }
    }

    @Test
    void valueTreeDetokenizationEnforcesTheCumulativeOutputLimit() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());
        String original = "x".repeat(PrivacyService.MAX_VALUE_TREE_STRING_CHARACTERS);

        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(
                    session.handle(),
                    original,
                    List.of(new PiiSpan("SECRET", 0, original.length(), 1.0))
            );
            int acceptedCopies = PrivacyService.MAX_TRANSFORMED_TEXT_CHARACTERS / original.length();
            List<String> accepted = Collections.nCopies(acceptedCopies, token);
            List<String> rejected = Collections.nCopies(acceptedCopies + 1, token);

            assertThat(service.detokenizeValueTree(session.handle(), accepted))
                    .isEqualTo(Collections.nCopies(acceptedCopies, original));
            assertValueTreeLimitFailure(
                    () -> service.detokenizeValueTree(session.handle(), rejected),
                    PrivacyPhase.DETOKENIZATION
            );
        }
    }

    @Test
    void valueTreeEnforcesTheCumulativeResolvedSpanLimit() {
        PiiAnalyzer analyzer = (text, options) -> {
            List<PiiSpan> spans = new ArrayList<>(text.length());
            for (int index = 0; index < text.length(); index++) {
                spans.add(new PiiSpan("PII", index, index + 1, 1.0));
            }
            return spans;
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        String accepted = "a".repeat(PiiAnalyzer.MAX_RESULT_SPANS);
        List<String> rejected = List.of(
                "a".repeat(PiiAnalyzer.MAX_RESULT_SPANS / 2),
                "b".repeat(PiiAnalyzer.MAX_RESULT_SPANS / 2 + 1)
        );

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenizeValueTree(session.handle(), accepted)).isInstanceOf(String.class);
            assertValueTreeLimitFailure(
                    () -> service.tokenizeValueTree(session.handle(), rejected),
                    PrivacyPhase.TOKENIZATION
            );
        }
    }

    private static PrivacyService serviceWithNoopAnalyzer() {
        return new PrivacyService(
                List.of((text, options) -> List.of()),
                PiiAnalysisOptions.defaults()
        );
    }

    private static Object nestedLists(int depth) {
        Object value = null;
        for (int index = 0; index < depth; index++) {
            value = Collections.singletonList(value);
        }
        return value;
    }

    private static void assertValueTreeLimitFailure(
            ThrowingOperation operation,
            PrivacyPhase phase
    ) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(phase);
                });
    }

    @FunctionalInterface
    private interface ThrowingOperation {

        void run();
    }
}
