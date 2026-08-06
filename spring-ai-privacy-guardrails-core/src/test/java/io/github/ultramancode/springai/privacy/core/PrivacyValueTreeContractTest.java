package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyValueTreeContractTest {

    @Test
    void valueTreeOperationsSupportJsonCompatibleJavaValues() {
        PrivacyService service = serviceWithNoopAnalyzer();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("null", null);
        nested.put("boolean", true);
        nested.put("string", "safe");
        nested.put("byte", (byte) 1);
        nested.put("short", (short) 2);
        nested.put("integer", 3);
        nested.put("long", 4L);
        nested.put("bigInteger", new BigInteger("5"));
        nested.put("bigDecimal", new BigDecimal("6.25"));
        nested.put("float", 7.5F);
        nested.put("double", 8.75D);
        nested.put("list", List.of("value", 9));

        try (PrivacySession session = service.openSession()) {
            Object tokenized = service.tokenizeValueTree(session.handle(), nested);

            assertThat(tokenized).isEqualTo(nested);
            assertThat(service.detokenizeValueTree(session.handle(), tokenized)).isEqualTo(nested);
        }
    }

    @Test
    void valueTreeOperationsRejectUnsupportedJavaValues() {
        PrivacyService service = serviceWithNoopAnalyzer();
        List<Object> unsupportedValues = List.of(
                new UnsupportedValue(),
                new UnsupportedRecord("private-value"),
                Set.of("value"),
                new byte[]{1},
                new Object[]{"value"},
                Optional.of("value"),
                new AtomicInteger(1),
                new UnsupportedBigInteger(),
                Double.NaN,
                Double.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY
        );

        try (PrivacySession session = service.openSession()) {
            for (Object unsupportedValue : unsupportedValues) {
                assertTreeFailure(
                        () -> service.tokenizeValueTree(session.handle(), unsupportedValue),
                        PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                        PrivacyPhase.TOKENIZATION
                );
                assertTreeFailure(
                        () -> service.detokenizeValueTree(session.handle(), unsupportedValue),
                        PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                        PrivacyPhase.DETOKENIZATION
                );
            }
        }
    }

    @Test
    void valueTreeOperationsRejectNonStringMapKeys() {
        PrivacyService service = serviceWithNoopAnalyzer();
        Map<Object, Object> input = new LinkedHashMap<>();
        input.put(1, "value");

        try (PrivacySession session = service.openSession()) {
            assertTreeFailure(
                    () -> service.tokenizeValueTree(session.handle(), input),
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    PrivacyPhase.TOKENIZATION
            );
            assertTreeFailure(
                    () -> service.detokenizeValueTree(session.handle(), input),
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    PrivacyPhase.DETOKENIZATION
            );
        }
    }

    @Test
    void valueTreeOperationsRejectCyclesWithoutRecursingIndefinitely() {
        PrivacyService service = serviceWithNoopAnalyzer();
        List<Object> cyclicList = new ArrayList<>();
        cyclicList.add(cyclicList);
        Map<String, Object> cyclicMap = new LinkedHashMap<>();
        cyclicMap.put("self", cyclicMap);

        try (PrivacySession session = service.openSession()) {
            for (Object cyclicValue : List.of(cyclicList, cyclicMap)) {
                assertTreeFailure(
                        () -> service.tokenizeValueTree(session.handle(), cyclicValue),
                        PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                        PrivacyPhase.TOKENIZATION
                );
                assertTreeFailure(
                        () -> service.detokenizeValueTree(session.handle(), cyclicValue),
                        PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                        PrivacyPhase.DETOKENIZATION
                );
            }
        }
    }

    @Test
    void valueTreeOperationsAllowSharedAcyclicContainers() {
        PrivacyService service = serviceWithNoopAnalyzer();
        List<Object> shared = new ArrayList<>(List.of("value"));
        List<Object> input = List.of(shared, shared);

        try (PrivacySession session = service.openSession()) {
            assertThat(service.tokenizeValueTree(session.handle(), input)).isEqualTo(input);
            assertThat(service.detokenizeValueTree(session.handle(), input)).isEqualTo(input);
        }
    }

    @Test
    void detokenizationKeepsMapKeysAsStringsForNumericTokens() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            String numericToken = service.tokenizeScalar(
                    session.handle(),
                    123,
                    List.of(new PiiSpan("NATIONAL_ID", 0, 3, 1.0))
            ).toString();

            Object restored = service.detokenizeValueTree(
                    session.handle(),
                    Map.of(numericToken, "safe")
            );

            assertThat(restored).isEqualTo(Map.of("123", "safe"));
            assertThat(((Map<?, ?>) restored).keySet()).allMatch(String.class::isInstance);
        }
    }

    @Test
    void tokenizationValidatesTheWholeTreeBeforeAnalyzingValues() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            return List.of(new PiiSpan("PERSON", 0, text.length(), 1.0));
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            assertTreeFailure(
                    () -> service.tokenizeValueTree(
                            session.handle(),
                            List.of("private-value", new UnsupportedValue())
                    ),
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    PrivacyPhase.TOKENIZATION
            );
        }

        assertThat(analysisCalls).hasValue(0);
    }

    private static PrivacyService serviceWithNoopAnalyzer() {
        return new PrivacyService(
                List.of((text, options) -> List.of()),
                PiiAnalysisOptions.defaults()
        );
    }

    private static void assertTreeFailure(
            ThrowingOperation operation,
            PrivacyFailureCode code,
            PrivacyPhase phase
    ) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(code);
                    assertThat(failure.phase()).isEqualTo(phase);
                    assertThat(failure.getMessage()).doesNotContain("private-value");
                    assertThat(failure.toString()).doesNotContain("private-value");
                });
    }

    @FunctionalInterface
    private interface ThrowingOperation {

        void run();
    }

    private static final class UnsupportedValue {

        @Override
        public String toString() {
            throw new AssertionError("Unsupported values must not be rendered");
        }
    }

    private record UnsupportedRecord(String value) {
    }

    private static final class UnsupportedBigInteger extends BigInteger {

        private UnsupportedBigInteger() {
            super("1");
        }

        @Override
        public String toString() {
            throw new AssertionError("Unsupported numbers must not be rendered");
        }
    }
}
