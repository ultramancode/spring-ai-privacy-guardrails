package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.EntityTypeRegistry;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiResolutionPolicy;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyJsonPayloadPolicyTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void strictJsonContractRejectsEveryNonblankParseFailure() {
        for (String invalidJson : List.of(
                "not-json",
                "[[PII_EMAIL_ADDRESS_0123456789abcdef0123456789abcdef_3]]",
                "[[PII_not-a-complete-token",
                "[[PII_PERSON__NAME_0123456789abcdef0123456789abcdef_1]]"
        )) {
            assertThatThrownBy(() -> PrivacyJsonPayloadTransformer.transformJsonOrText(
                    invalidJson,
                    scalar -> scalar,
                    text -> text,
                    PrivacyPhase.TOKENIZATION,
                    true
            )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOKENIZATION);
                assertThat(failure).hasMessage("Structured JSON payload is invalid");
            });
        }
    }

    @Test
    void jsonTransformerUsesLosslessNumbersWithoutWideningOrdinaryIntegers() {
        List<Class<?>> numberTypes = new ArrayList<>();
        String input = "[2,2147483648,9223372036854775808,0.1234567890123456789012345]";

        String result = PrivacyJsonPayloadTransformer.transformJsonOrText(
                input,
                scalar -> {
                    if (scalar instanceof Number number) {
                        numberTypes.add(number.getClass());
                    }
                    return scalar;
                },
                text -> text,
                PrivacyPhase.TOKENIZATION,
                true
        );

        assertThat(result).isEqualTo(input);
        assertThat(numberTypes).containsExactly(
                Integer.class,
                Long.class,
                BigInteger.class,
                BigDecimal.class
        );
    }

    @Test
    void tokenizeDecodesEscapedPiiAndPreservesUntouchedNumericLexemes() throws Exception {
        PrivacyService service = TestPrivacyServices.privacyService();
        String precise = "0.1234567890123456789012345";
        String input = "{\"email\":\"alice\\u0040example.com\",\"precise\":" + precise
                + ",\"scientific\":1e3}";

        try (PrivacySession session = service.openSession()) {
            PrivacyOutputPolicyExecutor.Result result = PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            );

            assertThat(result.blocked()).isFalse();
            assertThat(result.text())
                    .doesNotContain("alice@example.com", "alice\\u0040example.com")
                    .contains("\"precise\":" + precise)
                    .contains("\"scientific\":1e3");
            assertThat(OBJECT_MAPPER.readValue(
                    result.text(),
                    new TypeReference<Map<String, Object>>() { }
            )).containsKeys(
                    "email",
                    "precise",
                    "scientific"
            );
            assertThat(service.detokenize(session.handle(), result.text()))
                    .contains("alice@example.com");
        }
    }

    @Test
    void redactPreservesJsonAndHandlesEscapedStringAndExponentNumericPii() throws Exception {
        PrivacyService service = TestPrivacyServices.privacyService();
        String input = "{\"email\":\"alice\\u0040example.com\","
                + "\"phone\":8.21012345678e11,\"revision\":1e3}";

        try (PrivacySession session = service.openSession()) {
            PrivacyOutputPolicyExecutor.Result result = PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.REDACT
            );

            assertThat(result.blocked()).isFalse();
            assertThat(OBJECT_MAPPER.readValue(
                    result.text(),
                    new TypeReference<Map<String, Object>>() { }
            ))
                    .containsEntry("email", "[REDACTED_EMAIL_ADDRESS]")
                    .containsEntry("phone", "[REDACTED_PHONE_NUMBER]");
            assertThat(result.text())
                    .contains("\"revision\":1e3")
                    .doesNotContain("alice@example.com", "8.21012345678e11", "821012345678");
        }
    }

    @Test
    void blockDetectsEscapedStringAndExponentNumericPii() {
        PrivacyService service = TestPrivacyServices.privacyService();

        try (PrivacySession session = service.openSession()) {
            assertThat(PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    "{\"email\":\"alice\\u0040example.com\"}",
                    PrivacyOutputAction.BLOCK
            ).blocked()).isTrue();
            assertThat(PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    "{\"phone\":8.21012345678e11}",
                    PrivacyOutputAction.BLOCK
            ).blocked()).isTrue();
            assertThat(PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    "{\"revision\":1e3}",
                    PrivacyOutputAction.BLOCK
            ).blocked()).isFalse();
        }
    }

    @Test
    void extremePositiveAndNegativeExponentsFailBeforePlainExpansion() {
        for (String value : List.of(
                "1e999999999",
                "1e-999999999",
                "1e9999999999",
                "1e-9999999999"
        )) {
            assertPayloadLimit(() -> PrivacyJsonPayloadTransformer.transformJsonOrText(
                    value,
                    scalar -> scalar,
                    text -> text,
                    PrivacyPhase.OUTPUT_POLICY,
                    false
            ));
        }
    }

    @Test
    void numericLexemeAndExpandedValueLimitsHaveExplicitBoundaries() {
        String maximumInteger = "9".repeat(
                PrivacyJsonPayloadTransformer.MAX_NUMBER_LEXEME_CHARACTERS
        );

        assertThat(transformIdentity(maximumInteger)).isEqualTo(maximumInteger);
        assertPayloadLimit(() -> transformIdentity(maximumInteger + "9"));
        assertThat(transformIdentity("1e4095")).isEqualTo("1e4095");
        assertPayloadLimit(() -> transformIdentity("1e4096"));
    }

    @Test
    void jsonWritingStopsBeforeAReplacementExceedsTheTransformedOutputLimit() {
        String oversized = "x".repeat(
                PrivacyJsonPayloadTransformer.MAX_TRANSFORMED_PAYLOAD_CHARACTERS + 1
        );

        assertPayloadLimit(() -> PrivacyJsonPayloadTransformer.transformJsonOrText(
                "{\"value\":\"safe\"}",
                scalar -> "safe".equals(scalar) ? oversized : scalar,
                text -> text,
                PrivacyPhase.OUTPUT_POLICY,
                true
        ));
    }

    @Test
    void disclosureExpansionUsesTheCallingBoundaryPhaseForOutputLimitFailures() {
        PiiAnalyzer analyzer = (text, options) -> List.of();
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.defaults(),
                new EntityTypeRegistry(Map.of(), Set.of("SECRET")),
                PiiResolutionPolicy.defaults()
        );
        String original = "x".repeat(250_000);

        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(
                    session.handle(),
                    original,
                    List.of(new PiiSpan("SECRET", 0, original.length(), 1.0))
            );

            assertThatThrownBy(() -> PrivacyJsonPayloadTransformer.disclose(
                    service,
                    session.handle(),
                    "{\"tokens\":\"" + token.repeat(33) + "\"}",
                    Set.of("SECRET"),
                    PrivacyPhase.TOOL_INPUT,
                    true
            )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOOL_INPUT);
            });
        }
    }

    @Test
    void plainDecimalAnalysisDoesNotIntroduceExponentNotation() {
        AtomicReference<String> analyzedText = new AtomicReference<>();
        PiiAnalyzer analyzer = (text, options) -> {
            analyzedText.set(text);
            return List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        String input = "0.0000001";

        try (PrivacySession session = service.openSession()) {
            assertThat(PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            ).text()).isEqualTo(input);
        }
        assertThat(analyzedText).hasValue(input);
    }

    @Test
    void tinyPlainDecimalPiiUsesTheAnalyzedRepresentationForTokenization() throws Exception {
        PiiAnalyzer analyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                return text.equals("0.0000001")
                        ? List.of(new PiiSpan("NUMERIC_ID", 0, text.length(), 1.0))
                        : List.of();
            }

            @Override
            public Set<String> trustedEntityTypes() {
                return Set.of("NUMERIC_ID");
            }
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            String protectedPayload = PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    "0.0000001",
                    PrivacyOutputAction.TOKENIZE
            ).text();
            Object token = OBJECT_MAPPER.readValue(protectedPayload, Object.class);
            Object restored = service.detokenizeValueTree(session.handle(), token);

            assertThat(protectedPayload).doesNotContain("0.0000001");
            assertThat(restored).isInstanceOf(BigDecimal.class);
            assertThat(((BigDecimal) restored).compareTo(new BigDecimal("0.0000001"))).isZero();
        }
    }

    @Test
    void boundedProcessingRejectsPayloadScalarNodeAndDepthLimits() {
        String maximumNodeArray = "[" + "0,".repeat(
                PrivacyJsonPayloadTransformer.MAX_JSON_NODES - 2
        ) + "0]";
        String excessiveNodeArray = "[" + "0,".repeat(
                PrivacyJsonPayloadTransformer.MAX_JSON_NODES - 1
        ) + "0]";
        String maximumDepthArray = "[".repeat(PrivacyJsonPayloadTransformer.MAX_JSON_DEPTH)
                + "0"
                + "]".repeat(PrivacyJsonPayloadTransformer.MAX_JSON_DEPTH);

        assertPayloadLimit(() -> transformIdentity(
                "x".repeat(PrivacyJsonPayloadTransformer.MAX_PAYLOAD_CHARACTERS + 1)
        ));
        assertPayloadLimit(() -> transformIdentity(
                "\"" + "x".repeat(PrivacyJsonPayloadTransformer.MAX_STRING_SCALAR_CHARACTERS + 1) + "\""
        ));
        assertThat(transformIdentity(maximumNodeArray)).isEqualTo(maximumNodeArray);
        assertPayloadLimit(() -> transformIdentity(excessiveNodeArray));
        assertThat(transformIdentity(maximumDepthArray)).isEqualTo(maximumDepthArray);
        assertPayloadLimit(() -> transformIdentity(
                "[".repeat(PrivacyJsonPayloadTransformer.MAX_JSON_DEPTH + 1)
                        + "0"
                        + "]".repeat(PrivacyJsonPayloadTransformer.MAX_JSON_DEPTH + 1)
        ));
    }

    @Test
    void excessiveNodeCountFailsBeforeAnalyzerInvocation() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            return List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        String input = "[" + "0,".repeat(PrivacyJsonPayloadTransformer.MAX_JSON_NODES - 1) + "0]";

        try (PrivacySession session = service.openSession()) {
            assertPayloadLimit(() -> PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            ));
        }
        assertThat(analysisCalls).hasValue(0);
    }

    @Test
    void maximumNodeCountStillAllowsContainerEndTokens() {
        String input = "[" + "[],".repeat(
                PrivacyJsonPayloadTransformer.MAX_JSON_NODES - 2
        ) + "[]]";

        assertThat(transformIdentity(input)).isEqualTo(input);
    }

    @Test
    void scalarLargerThanBatchTargetIsAnalyzedIntact() {
        AtomicInteger analysisCalls = new AtomicInteger();
        AtomicReference<String> analyzedText = new AtomicReference<>();
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            analyzedText.set(text);
            return List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        String scalar = "x".repeat(PrivacyJsonPayloadTransformer.ANALYSIS_BATCH_TARGET_CHARACTERS + 1);
        String input = "\"" + scalar + "\"";

        try (PrivacySession session = service.openSession()) {
            assertThat(PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            ).text()).isEqualTo(input);
        }
        assertThat(analysisCalls).hasValue(1);
        assertThat(analyzedText).hasValue(scalar);
    }

    @Test
    void expandedNumericAnalysisWorkFailsBeforeAnalyzerInvocation() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            return List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        String input = IntStream.rangeClosed(1, 300)
                .mapToObj(index -> index + "e4000")
                .collect(Collectors.joining(",", "[", "]"));

        try (PrivacySession session = service.openSession()) {
            assertPayloadLimit(() -> PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            ));
        }
        assertThat(analysisCalls).hasValue(0);
    }

    @Test
    void sequentialScalarBatchesShareOneAnalyzerResultCardinalityBudget() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            return Pattern.compile(".", Pattern.DOTALL)
                    .matcher(text)
                    .results()
                    .map(match -> new PiiSpan("CHARACTER", match.start(), match.end(), 1.0))
                    .toList();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        String input = IntStream.range(0, 4)
                .mapToObj(index -> "\"" + Character.toString('a' + index).repeat(30_000) + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        try (PrivacySession session = service.openSession()) {
            assertPayloadLimit(() -> PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            ));
        }
        assertThat(analysisCalls).hasValue(4);
    }

    @Test
    void moreThan256UniqueScalarsAreProtectedInOneCharacterBoundedBatch() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                analysisCalls.incrementAndGet();
                return Pattern.compile("secret-\\d+")
                        .matcher(text)
                        .results()
                        .map(match -> new PiiSpan("SECRET", match.start(), match.end(), 1.0))
                        .toList();
            }

            @Override
            public Set<String> trustedEntityTypes() {
                return Set.of("SECRET");
            }
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        String input = IntStream.range(0, 600)
                .mapToObj(index -> "\"secret-" + index + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        try (PrivacySession session = service.openSession()) {
            String protectedPayload = PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            ).text();

            assertThat(protectedPayload).doesNotContain("secret-");
            assertThat(service.detokenize(session.handle(), protectedPayload)).isEqualTo(input);
        }
        assertThat(analysisCalls).hasValue(1);
    }

    @Test
    void largeUniqueScalarSetsProtectValuesAcrossSequentialCharacterBoundedBatches() {
        AtomicInteger analysisCalls = new AtomicInteger();
        String firstSecret = "value-0-" + "x".repeat(80);
        String lastSecret = "value-799-" + "x".repeat(80);
        PiiAnalyzer analyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                analysisCalls.incrementAndGet();
                List<PiiSpan> spans = new ArrayList<>();
                for (String secret : List.of(firstSecret, lastSecret)) {
                    int start = text.indexOf(secret);
                    if (start >= 0) {
                        spans.add(new PiiSpan("SECRET", start, start + secret.length(), 1.0));
                    }
                }
                return spans;
            }

            @Override
            public Set<String> trustedEntityTypes() {
                return Set.of("SECRET");
            }
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        String input = IntStream.range(0, 800)
                .mapToObj(index -> "\"value-" + index + "-" + "x".repeat(80) + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        try (PrivacySession session = service.openSession()) {
            String protectedPayload = PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            ).text();

            assertThat(protectedPayload).doesNotContain(firstSecret, lastSecret);
            assertThat(service.detokenize(session.handle(), protectedPayload)).isEqualTo(input);
        }
        assertThat(analysisCalls.get()).isGreaterThan(1).isLessThan(800);
    }

    @Test
    void analyzerSpanCrossingScalarBoundaryFailsClosed() {
        PiiAnalyzer analyzer = (text, options) -> text.indexOf('\0') >= 0
                ? List.of(new PiiSpan("PII", 0, text.length(), 1.0))
                : List.of();
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    "[\"left\",\"right\"]",
                    PrivacyOutputAction.TOKENIZE
            )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                assertThat(failure.phase()).isEqualTo(PrivacyPhase.OUTPUT_POLICY);
                assertThat(failure)
                        .hasMessage("Analyzer result crossed a structured scalar boundary")
                        .hasMessageNotContaining("left")
                        .hasMessageNotContaining("right");
            });
        }
    }

    @Test
    void repeatedJsonScalarsShareOneAnalyzerResultWithinThePayload() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            return List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        String input = "[" + "\"safe\",".repeat(100) + "\"safe\"]";

        try (PrivacySession session = service.openSession()) {
            assertThat(PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            ).text()).isEqualTo(input);
        }
        assertThat(analysisCalls).hasValue(1);
    }

    @Test
    void tokenizeAllowsUnicodeEscapeInOrdinaryOutputText() {
        assertMalformedEscapedTextAllowed(PrivacyOutputAction.TOKENIZE);
    }

    @Test
    void redactAllowsUnicodeEscapeInOrdinaryOutputText() {
        assertMalformedEscapedTextAllowed(PrivacyOutputAction.REDACT);
    }

    @Test
    void blockAllowsUnicodeEscapeInOrdinaryOutputText() {
        assertMalformedEscapedTextAllowed(PrivacyOutputAction.BLOCK);
    }

    @Test
    void attackerSuppliedRedactionMarkersAreNotImplicitlyTrusted() {
        PiiAnalyzer analyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                if (text.contains("Alice")) {
                    int start = text.indexOf("Alice");
                    return List.of(new PiiSpan("PERSON", start, start + "Alice".length(), 1.0));
                }
                if (text.contains("REDACTED")) {
                    int start = text.indexOf("REDACTED");
                    return List.of(new PiiSpan("MARKER", start, start + "REDACTED".length(), 1.0));
                }
                return List.of();
            }

            @Override
            public Set<String> trustedEntityTypes() {
                return Set.of("MARKER");
            }
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            PrivacyOutputPolicyExecutor.Result first = PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    "Alice",
                    PrivacyOutputAction.REDACT
            );
            PrivacyOutputPolicyExecutor.Result second = PrivacyOutputPolicyExecutor.apply(
                    service,
                    session.handle(),
                    first.text(),
                    PrivacyOutputAction.REDACT
            );

            assertThat(first.text()).isEqualTo("[REDACTED_PERSON]");
            assertThat(second.text())
                    .isNotEqualTo(first.text())
                    .contains("[REDACTED_MARKER]");
        }
    }

    private void assertMalformedEscapedTextAllowed(PrivacyOutputAction action) {
        PrivacyService service = TestPrivacyServices.privacyService();
        try (PrivacySession session = service.openSession()) {
            for (String malformed : List.of(
                    "{\"email\":\"alice\\u0040example.com\"",
                    "\"alice\\u0040example.com\" trailing",
                    "alice\\u0040example.com trailing"
            )) {
                PrivacyOutputPolicyExecutor.Result result = PrivacyOutputPolicyExecutor.apply(
                        service,
                        session.handle(),
                        malformed,
                        action
                );
                assertThat(result.text()).isEqualTo(malformed);
                assertThat(result.blocked()).isFalse();
            }
        }
    }

    private String transformIdentity(String value) {
        return PrivacyJsonPayloadTransformer.transformJsonOrText(
                value,
                scalar -> scalar,
                text -> text,
                PrivacyPhase.OUTPUT_POLICY,
                false
        );
    }

    private void assertPayloadLimit(ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.OUTPUT_POLICY);
                    assertThat(failure).hasMessage("Privacy payload exceeded the bounded processing limit");
                });
    }
}
