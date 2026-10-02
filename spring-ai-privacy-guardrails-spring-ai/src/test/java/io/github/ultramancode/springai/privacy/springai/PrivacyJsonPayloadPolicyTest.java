package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.EntityTypeRegistry;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailureObserver;
import io.github.ultramancode.springai.privacy.core.PiiResolutionPolicy;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import io.github.ultramancode.springai.privacy.core.RegexPiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.RegexPiiRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyJsonPayloadPolicyTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {"  {\"value\":\"ok\"}  ", "{\"value\":\"\\u006f\\u006b\"}", "  ok  ", "   "})
    void blockChecksTheUnchangedOutputLength(String payload) {
        PiiAnalyzer analyzer = (text, options, processingLimits) -> List.of();
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder()
                .maxOutputCharacters(payload.length())
                .build();
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);

        try (PrivacySession session = service.openSession()) {
            assertThat(applyOutputPolicy(service, session.handle(), payload, PrivacyOutputAction.BLOCK))
                    .isEqualTo(payload);
            assertPayloadLimit(() -> applyOutputPolicy(
                    service, session.handle(), payload + " ", PrivacyOutputAction.BLOCK));
        }
    }

    @Test
    void redactProtectsNestedJsonValues() {
        PiiAnalyzer analyzer = (text, options, limits) ->
                List.of(new PiiSpan("PII", 0, text.length(), 1.0));
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            String result = applyOutputPolicy(
                    service, session.handle(), "{\"name\":\"Alice\",\"nested\":[{\"id\":123}]}",
                    PrivacyOutputAction.REDACT);

            assertThat(result)
                    .isEqualTo("{\"name\":\"[REDACTED_PII]\",\"nested\":[{\"id\":\"[REDACTED_PII]\"}]}");
        }
    }

    @ParameterizedTest
    @EnumSource(value = PrivacyOutputAction.class, names = {"REDACT", "BLOCK"})
    void jsonOutputPreservesResolvedTypeConflictsOutsideTheDetectionAllowlist(PrivacyOutputAction action) {
        PiiAnalyzer analyzer = (text, options, limits) -> List.of(
                new PiiSpan("PERSON", 0, text.length(), 1.0),
                new PiiSpan("PHONE_NUMBER", 0, text.length(), 1.0));
        PrivacyService service = new PrivacyService(List.of(analyzer),
                new PiiAnalysisOptions("en", List.of("PERSON", "PHONE_NUMBER"), 0.0));

        try (PrivacySession session = service.openSession()) {
            String input = "{\"value\":[\"Alice\",123.00]}";
            if (action == PrivacyOutputAction.BLOCK) {
                assertThatThrownBy(() -> applyOutputPolicy(service, session.handle(), input, action))
                        .isInstanceOf(PrivacyOutputBlockedException.class);
            } else {
                String result = applyOutputPolicy(service, session.handle(), input, action);
                assertThat(result)
                        .isEqualTo("{\"value\":[\"[REDACTED_PII]\",\"[REDACTED_PII]\"]}");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = PrivacyOutputAction.class, names = {"REDACT", "BLOCK"})
    void jsonOutputUsesBatchResultsEvenWhenTheyExceedTheSessionCache(PrivacyOutputAction action) throws Exception {
        AtomicInteger singleCalls = new AtomicInteger();
        AtomicInteger batchCalls = new AtomicInteger();
        PiiAnalyzer analyzer = TestPrivacyServices.countingSegmentedAnalyzer(
                singleCalls, batchCalls, Set.of(), texts -> texts.stream()
                        .map(text -> List.of(new PiiSpan("PERSON", 0, text.length(), 1.0)))
                        .toList());
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxAnalysisSegments(128).build();
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);
        List<String> values = new ArrayList<>();
        // Exceeds the session's 256-entry analysis cache.
        for (int index = 0; index < 320; index++) {
            values.add("person-" + index);
        }

        try (PrivacySession session = service.openSession()) {
            String input = OBJECT_MAPPER.writeValueAsString(values);
            if (action == PrivacyOutputAction.BLOCK) {
                assertThatThrownBy(() -> applyOutputPolicy(service, session.handle(), input, action))
                        .isInstanceOf(PrivacyOutputBlockedException.class);
            } else {
                String result = applyOutputPolicy(service, session.handle(), input, action);
                List<String> redacted = OBJECT_MAPPER.readValue(result, new TypeReference<>() {});
                assertThat(redacted).hasSize(values.size()).containsOnly("[REDACTED_PERSON]");
            }
        }
        assertThat(singleCalls).hasValue(0);
        assertThat(batchCalls).hasValue(3);
    }

    @Test
    void jsonOutputExcludesOwnedTokensButStillBlocksUntrustedRedactionMarkers() {
        PiiAnalyzer analyzer = (text, options, limits) ->
                List.of(new PiiSpan("PII", 0, text.length(), 1.0));
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), "Alice");
            String tokenPayload = OBJECT_MAPPER.writeValueAsString(List.of(token));
            assertThat(applyOutputPolicy(
                    service, session.handle(), tokenPayload, PrivacyOutputAction.REDACT))
                    .isEqualTo(tokenPayload);
            assertThat(applyOutputPolicy(
                    service, session.handle(), tokenPayload, PrivacyOutputAction.BLOCK)).isEqualTo(tokenPayload);

            String mixedPayload = OBJECT_MAPPER.writeValueAsString(List.of(token + " Bob"));
            assertThat(applyOutputPolicy(
                    service, session.handle(), mixedPayload, PrivacyOutputAction.REDACT))
                    .isEqualTo(OBJECT_MAPPER.writeValueAsString(List.of(token + "[REDACTED_PII]")));
            assertThatThrownBy(() -> applyOutputPolicy(
                    service, session.handle(), mixedPayload, PrivacyOutputAction.BLOCK))
                    .isInstanceOf(PrivacyOutputBlockedException.class);
            assertThatThrownBy(() -> applyOutputPolicy(
                    service, session.handle(), "[\"[REDACTED_PII]\"]", PrivacyOutputAction.BLOCK))
                    .isInstanceOf(PrivacyOutputBlockedException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"value\":\"Alice\",\"value\":821012345678}",
            "{\"value\":\"Alice\",\"\\u0076alue\":821012345678}"
    })
    void redactPreservesDuplicatePropertiesAndProtectsTheirValues(String input) {
        PrivacyService service = TestPrivacyServices.privacyService();

        try (PrivacySession session = service.openSession()) {
            String result = applyOutputPolicy(
                    service, session.handle(), input, PrivacyOutputAction.REDACT);

            assertThat(result).isEqualTo(
                    "{\"value\":\"[REDACTED_PERSON]\",\"value\":\"[REDACTED_PHONE_NUMBER]\"}");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "[\"ab\"]"})
    void analysisLimitFailuresUseTheCallingBoundaryPhase(String payload) {
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule("PERSON", ".", 1.0, 0)));
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(1).build();
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> PrivacyJsonPayloadTransformer.tokenize(
                    service, session.handle(), payload, PrivacyPhase.TOOL_INPUT, false))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOOL_INPUT);
                    });
            assertPayloadLimit(() -> applyOutputPolicy(
                    service, session.handle(), payload, PrivacyOutputAction.BLOCK));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tokenRestorationLimitFailuresUseTheCallingBoundaryPhase(boolean json) {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxOutputCharacters(60).build();
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), limits);
        String original = "x".repeat(61);

        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), original,
                    List.of(new PiiSpan("PERSON", 0, original.length(), 1.0)));
            String payload = json ? "[\"" + token + "\"]" : token;
            assertThatThrownBy(() -> PrivacyJsonPayloadTransformer.restoreKnownTokens(
                    service, session.handle(), payload, PrivacyPhase.OUTPUT_POLICY))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.OUTPUT_POLICY);
                    });
        }
    }

    @Test
    void strictJsonContractRejectsEveryNonblankParseFailure() {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults());
        try (PrivacySession session = service.openSession()) {
            // Unquoted token-like text must not bypass strict JSON validation.
            for (String invalidJson : List.of(
                    "not-json",
                    "[[PII_EMAIL_ADDRESS_0123456789abcdef0123456789abcdef_3]]",
                    "[[PII_not-a-complete-token",
                    "[[PII_PERSON__NAME_0123456789abcdef0123456789abcdef_1]]"
            )) {
                assertThatThrownBy(() -> PrivacyJsonPayloadTransformer.discloseWithOutcome(
                        service, session.handle(), invalidJson, Set.of(), PrivacyPhase.TOOL_INPUT, true
                )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOOL_INPUT);
                    assertThat(failure).hasMessage("Structured JSON payload is invalid");
                });
            }
        }
    }

    @Test
    void jsonTransformerUsesLosslessNumbersWithoutWideningOrdinaryIntegers() {
        List<Class<?>> numberTypes = new ArrayList<>();
        // An ordinary int, Integer.MAX_VALUE + 1, Long.MAX_VALUE + 1, and a high-precision decimal.
        String input = "[2,2147483648,9223372036854775808,0.1234567890123456789012345]";

        PrivacyJsonDocumentProcessor processor = new PrivacyJsonDocumentProcessor(PrivacyProcessingLimits.defaults());
        String result = processor.transform(
                input,
                scalar -> {
                    if (scalar instanceof Number number) {
                        numberTypes.add(number.getClass());
                    }
                    return scalar;
                },
                PrivacyPhase.TOKENIZATION
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
            String result = applyOutputPolicy(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            );

            assertThat(result)
                    .doesNotContain("alice@example.com", "alice\\u0040example.com")
                    .contains("\"precise\":" + precise)
                    .contains("\"scientific\":1e3");
            assertThat(OBJECT_MAPPER.readValue(
                    result,
                    new TypeReference<Map<String, Object>>() { }
            )).containsKeys(
                    "email",
                    "precise",
                    "scientific"
            );
            assertThat(service.detokenize(session.handle(), result))
                    .contains("alice@example.com");
        }
    }

    @Test
    void redactPreservesJsonAndHandlesEscapedStringAndExponentNumericPii() throws Exception {
        PrivacyService service = TestPrivacyServices.privacyService();
        String input = "{\"email\":\"alice\\u0040example.com\","
                + "\"phone\":8.21012345678e11,\"revision\":1e3}";

        try (PrivacySession session = service.openSession()) {
            String result = applyOutputPolicy(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.REDACT
            );

            assertThat(OBJECT_MAPPER.readValue(
                    result,
                    new TypeReference<Map<String, Object>>() { }
            ))
                    .containsEntry("email", "[REDACTED_EMAIL_ADDRESS]")
                    .containsEntry("phone", "[REDACTED_PHONE_NUMBER]");
            assertThat(result)
                    .contains("\"revision\":1e3")
                    .doesNotContain("alice@example.com", "8.21012345678e11", "821012345678");
        }
    }

    @Test
    void blockDetectsPiiInJsonValues() {
        PrivacyService service = TestPrivacyServices.privacyService();

        try (PrivacySession session = service.openSession()) {
            for (String input : List.of(
                    "{\"email\":\"alice\\u0040example.com\"}",
                    "{\"phone\":8.21012345678e11}",
                    "{\"value\":\"Alice\",\"value\":\"safe\"}",
                    "{\"value\":\"safe\",\"value\":\"Alice\"}"
            )) {
                assertThatThrownBy(() -> applyOutputPolicy(
                        service, session.handle(), input, PrivacyOutputAction.BLOCK
                )).isInstanceOf(PrivacyOutputBlockedException.class);
            }
            assertThat(applyOutputPolicy(
                    service,
                    session.handle(),
                    "{\"revision\":1e3,\"revision\":2e3}",
                    PrivacyOutputAction.BLOCK
            )).isEqualTo("{\"revision\":1e3,\"revision\":2e3}");
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
            assertPayloadLimit(() -> restoreWithoutTokens(value, PrivacyProcessingLimits.defaults()));
        }
    }

    @Test
    void numericLexemesAndExpandedValuesRespectTheCharacterLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxValueTreeCharacters(5).build();
        assertThat(restoreWithoutTokens("99999", limits)).isEqualTo("99999");
        assertPayloadLimit(() -> restoreWithoutTokens("999999", limits));
        assertThat(restoreWithoutTokens("1e4", limits)).isEqualTo("1e4");
        assertPayloadLimit(() -> restoreWithoutTokens("1e5", limits));
        assertThat(restoreWithoutTokens("0e100", limits)).isEqualTo("0e100");
    }

    @Test
    void jsonOperationsHonorConfiguredTextAndDepthLimits() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder()
                .maxTextCharacters(100)
                .maxDepth(2)
                .build();
        PrivacyService service = new PrivacyService(
                List.of((text, options, processingLimits) -> List.of()), PiiAnalysisOptions.defaults(), limits);
        String maximumPayload = "{\"value\":\"" + "x".repeat(88) + "\"}";
        String excessivePayload = "{\"value\":\"" + "x".repeat(89) + "\"}";
        String maximumDepth = "[[0]]";
        String excessiveDepth = "[[[0]]]";

        try (PrivacySession session = service.openSession()) {
            assertThat(PrivacyJsonPayloadTransformer.redact(
                    service, session.handle(), maximumPayload, PrivacyPhase.OUTPUT_POLICY, true))
                    .isEqualTo(maximumPayload);
            assertThat(PrivacyJsonPayloadTransformer.redact(
                    service, session.handle(), maximumDepth, PrivacyPhase.OUTPUT_POLICY, true))
                    .isEqualTo(maximumDepth);
            assertThat(PrivacyJsonPayloadTransformer.restoreKnownTokens(
                    service, session.handle(), maximumDepth, PrivacyPhase.OUTPUT_POLICY))
                    .isEqualTo(maximumDepth);
            assertPayloadLimit(() -> PrivacyJsonPayloadTransformer.redact(
                    service, session.handle(), excessivePayload, PrivacyPhase.OUTPUT_POLICY, true));
            assertPayloadLimit(() -> PrivacyJsonPayloadTransformer.redact(
                    service, session.handle(), excessiveDepth, PrivacyPhase.OUTPUT_POLICY, true));
        }
    }

    @Test
    void jsonWritingRejectsRestoredValuesWhoseEscapingExceedsTheOutputLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxOutputCharacters(80).build();
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), limits);
        String original = "\"".repeat(70);

        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), original,
                    List.of(new PiiSpan("PERSON", 0, original.length(), 1.0)));
            String payload = "{\"value\":\"" + token + "\"}";
            assertThat(service.detokenize(session.handle(), token)).isEqualTo(original);
            assertPayloadLimit(() -> PrivacyJsonPayloadTransformer.restoreKnownTokens(
                    service, session.handle(), payload, PrivacyPhase.OUTPUT_POLICY));
        }
    }

    @Test
    void blankPayloadsRespectTheConfiguredOutputLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxOutputCharacters(2).build();
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), limits);

        assertThat(restoreWithoutTokens("  ", limits)).isEqualTo("  ");
        assertPayloadLimit(() -> restoreWithoutTokens("   ", limits));
        try (PrivacySession session = service.openSession()) {
            assertPayloadLimit(() -> PrivacyJsonPayloadTransformer.redact(
                    service, session.handle(), "   ", PrivacyPhase.OUTPUT_POLICY, false));
        }
    }

    @Test
    void disclosureExpansionUsesTheCallingBoundaryPhaseForOutputLimitFailures() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxOutputCharacters(200).build();
        PiiAnalyzer analyzer = (text, options, processingLimits) -> List.of();
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.defaults(),
                new EntityTypeRegistry(Map.of(), Set.of("SECRET")),
                PiiResolutionPolicy.defaults(),
                PiiAnalyzerFailureObserver.noop(),
                limits
        );
        String original = "x".repeat(100);

        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(
                    session.handle(),
                    original,
                    List.of(new PiiSpan("SECRET", 0, original.length(), 1.0))
            );

            assertThatThrownBy(() -> PrivacyJsonPayloadTransformer.discloseWithOutcome(
                    service,
                    session.handle(),
                    "{\"tokens\":\"" + token.repeat(3) + "\"}",
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
    void tinyPlainDecimalPiiUsesTheAnalyzedRepresentationForTokenization() throws Exception {
        PiiAnalyzer analyzer = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(
                    String text,
                    PiiAnalysisOptions options,
                    PrivacyProcessingLimits limits
            ) {
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
            String protectedPayload = applyOutputPolicy(
                    service,
                    session.handle(),
                    "0.0000001",
                    PrivacyOutputAction.TOKENIZE
            );
            Object token = OBJECT_MAPPER.readValue(protectedPayload, Object.class);
            Object restored = service.detokenizeValueTree(session.handle(), token);

            assertThat(protectedPayload).doesNotContain("0.0000001");
            assertThat(restored).isInstanceOf(BigDecimal.class);
            assertThat(((BigDecimal) restored).compareTo(new BigDecimal("0.0000001"))).isZero();
        }
    }

    @Test
    void jsonStringsUseTheConfiguredCharacterLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxValueTreeCharacters(5).build();

        assertThat(restoreWithoutTokens("\"abcde\"", limits)).isEqualTo("\"abcde\"");
        assertPayloadLimit(() -> restoreWithoutTokens("\"abcdef\"", limits));
        assertThat(restoreWithoutTokens("[\"ab\",\"cde\"]", limits)).isEqualTo("[\"ab\",\"cde\"]");
        assertPayloadLimit(() -> restoreWithoutTokens("[\"abc\",\"def\"]", limits));
    }

    @Test
    void jsonNodeLimitCountsContainersAndScalarsBeforeAnalysis() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxValueTreeNodes(3).build();
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options, processingLimits) -> {
            analysisCalls.incrementAndGet();
            return List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);

        try (PrivacySession session = service.openSession()) {
            assertThat(applyOutputPolicy(
                    service, session.handle(), "[[],[]]", PrivacyOutputAction.TOKENIZE))
                    .isEqualTo("[[],[]]");
            assertPayloadLimit(() -> applyOutputPolicy(
                    service, session.handle(), "[0,1,2]", PrivacyOutputAction.TOKENIZE));
        }
        assertThat(analysisCalls).hasValue(0);
    }

    @Test
    void batchingPreservesScalarIsolationForBoundarySensitiveRegexRules() {
        PrivacyService service = new PrivacyService(
                List.of(new RegexPiiAnalyzer(List.of(
                        new RegexPiiRule("EMPLOYEE_ID", "^EMP-[0-9]{4}$", 1.0, 0),
                        new RegexPiiRule("API_KEY", "\\AKEY-[0-9]{4}\\z", 1.0, 0)
                ))),
                PiiAnalysisOptions.defaults()
        );
        List<String> inputs = List.of(
                "[\"EMP-1234\",\"safe\"]",
                "[\"safe\",\"EMP-1234\",\"other\"]",
                "[\"safe\",\"EMP-1234\"]",
                "{\"safe\":\"value\",\"employee\":\"EMP-1234\"}",
                "[\"safe\",\"KEY-1234\"]"
        );

        for (String input : inputs) {
            try (PrivacySession session = service.openSession()) {
                String protectedPayload = applyOutputPolicy(
                        service,
                        session.handle(),
                        input,
                        PrivacyOutputAction.TOKENIZE
                );

                assertThat(protectedPayload).doesNotContain("EMP-1234", "KEY-1234");
                assertThat(service.detokenize(session.handle(), protectedPayload)).isEqualTo(input);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expandedNumericBatchKeepsTheOriginalJsonCharacterBudget(boolean disclose) {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder()
                .maxValueTreeCharacters(6)
                .maxTextCharacters(10)
                .build();
        List<String> analyzedTexts = new ArrayList<>();
        PiiAnalyzer analyzer = (text, options, processingLimits) -> {
            analyzedTexts.add(text);
            return List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);
        String input = "[1e3,2e3]";

        try (PrivacySession session = service.openSession()) {
            String output = disclose
                    ? PrivacyJsonPayloadTransformer.discloseWithOutcome(
                            service, session.handle(), input, Set.of("PERSON"), PrivacyPhase.TOOL_INPUT, true)
                            .payload()
                    : PrivacyJsonPayloadTransformer.tokenize(
                            service, session.handle(), input, PrivacyPhase.TOOL_INPUT, true);
            assertThat(output).isEqualTo(input);
            assertThat(analyzedTexts).containsExactly("1000", "2000");
        }
    }

    @Test
    void expandedNumericAnalysisUsesTheConfiguredTextLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxTextCharacters(10).build();
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options, processingLimits) -> {
            analysisCalls.incrementAndGet();
            return List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);

        try (PrivacySession session = service.openSession()) {
            assertThat(applyOutputPolicy(
                    service, session.handle(), "1e9", PrivacyOutputAction.TOKENIZE))
                    .isEqualTo("1e9");
            assertThat(analysisCalls).hasValue(1);
            for (String input : List.of("1e10", "[1e5,2e5]")) {
                assertPayloadLimit(() -> applyOutputPolicy(
                        service, session.handle(), input, PrivacyOutputAction.TOKENIZE));
            }
        }
        assertThat(analysisCalls).hasValue(1);
    }

    @ParameterizedTest
    @EnumSource(value = PrivacyOutputAction.class, names = {"TOKENIZE", "REDACT", "BLOCK"})
    void jsonBatchingRespectsSegmentCountAndSkipsAnalyzerOnRepeatedInput(PrivacyOutputAction action) {
        AtomicInteger singleCalls = new AtomicInteger();
        AtomicInteger batchCalls = new AtomicInteger();
        PiiAnalyzer analyzer = TestPrivacyServices.countingSegmentedAnalyzer(
                singleCalls, batchCalls, Set.of(), texts -> {
                    assertThat(texts).hasSize(1);
                    return List.of(List.of());
                });
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxAnalysisSegments(1).build();
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);
        String input = "[\"a\",\"b\"]";

        try (PrivacySession session = service.openSession()) {
            assertThat(applyOutputPolicy(
                    service, session.handle(), input, action)).isEqualTo(input);
            assertThat(batchCalls).hasValue(2);

            assertThat(applyOutputPolicy(
                    service, session.handle(), input, action)).isEqualTo(input);
            assertThat(batchCalls).hasValue(2);
        }
        assertThat(singleCalls).hasValue(0);
    }

    @Test
    void rawSpanLimitCountsOverlappingSpansAcrossSegmentBatches() {
        AtomicInteger singleCalls = new AtomicInteger();
        AtomicInteger batchCalls = new AtomicInteger();
        PiiAnalyzer analyzer = TestPrivacyServices.countingSegmentedAnalyzer(
                singleCalls, batchCalls, Set.of(), texts -> {
                    assertThat(texts).hasSize(1);
                    return List.of(List.of(new PiiSpan("CHARACTER", 0, 1, 0.9),
                            new PiiSpan("CHARACTER", 0, 1, 1.0)));
                });
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder()
                .maxAnalysisSegments(1)
                .maxResultSpans(3)
                .build();
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);

        try (PrivacySession session = service.openSession()) {
            assertPayloadLimit(() -> applyOutputPolicy(
                    service, session.handle(), "[\"a\",\"b\"]", PrivacyOutputAction.REDACT));
        }
        assertThat(batchCalls).hasValue(2);
        assertThat(singleCalls).hasValue(0);
    }

    @Test
    void distinctJsonScalarsAreProtectedWithOneSegmentedAnalysis() {
        AtomicInteger scalarAnalysisCalls = new AtomicInteger();
        AtomicInteger segmentedAnalysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = TestPrivacyServices.countingSegmentedAnalyzer(
                scalarAnalysisCalls,
                segmentedAnalysisCalls,
                Set.of("SECRET"),
                texts -> texts.stream()
                        .map(text -> Pattern.compile("secret-\\d+")
                                .matcher(text)
                                .results()
                                .map(match -> new PiiSpan(
                                        "SECRET",
                                        match.start(),
                                        match.end(),
                                        1.0
                                ))
                                .toList())
                        .toList()
        );
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        String input = "[\"secret-0\",\"secret-1\",\"secret-2\"]";

        try (PrivacySession session = service.openSession()) {
            String protectedPayload = applyOutputPolicy(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            );

            assertThat(protectedPayload).doesNotContain("secret-");
            assertThat(service.detokenize(session.handle(), protectedPayload)).isEqualTo(input);
        }
        assertThat(scalarAnalysisCalls).hasValue(0);
        assertThat(segmentedAnalysisCalls).hasValue(1);
    }

    @Test
    void jsonProtectsValuesAcrossMultipleAnalysisBatches() {
        AtomicInteger scalarAnalysisCalls = new AtomicInteger();
        AtomicInteger segmentedAnalysisCalls = new AtomicInteger();
        String firstSecret = "value-0";
        String lastSecret = "value-7";
        PiiAnalyzer analyzer = TestPrivacyServices.countingSegmentedAnalyzer(
                scalarAnalysisCalls,
                segmentedAnalysisCalls,
                Set.of("SECRET"),
                texts -> texts.stream()
                        .map(text -> {
                            if (text.equals(firstSecret) || text.equals(lastSecret)) {
                                return List.of(new PiiSpan(
                                        "SECRET",
                                        0,
                                        text.length(),
                                        1.0
                                ));
                            }
                            return List.<PiiSpan>of();
                        })
                        .toList()
        );
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxAnalysisSegments(3).build();
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), limits);
        String input = """
                ["value-0","value-1","value-2","value-3","value-4","value-5","value-6","value-7"]
                """.trim();

        try (PrivacySession session = service.openSession()) {
            String protectedPayload = applyOutputPolicy(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            );

            assertThat(protectedPayload).doesNotContain(firstSecret, lastSecret);
            assertThat(service.detokenize(session.handle(), protectedPayload)).isEqualTo(input);
        }
        assertThat(scalarAnalysisCalls).hasValue(0);
        assertThat(segmentedAnalysisCalls).hasValue(3);
    }

    @Test
    void analyzerSpanOutsideItsScalarFailsClosed() {
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
                        List.of(new PiiSpan("PII", 0, texts.get(0).length() + 1, 1.0)),
                        List.of()
                );
            }
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> applyOutputPolicy(
                    service,
                    session.handle(),
                    "[\"left\",\"right\"]",
                    PrivacyOutputAction.TOKENIZE
            )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                assertThat(failure.code())
                        .isEqualTo(PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION);
                assertThat(failure.phase()).isEqualTo(PrivacyPhase.ANALYSIS);
                assertThat(failure)
                        .hasMessageNotContaining("left")
                        .hasMessageNotContaining("right");
            });
        }
    }

    @Test
    void repeatedJsonScalarsShareOneAnalyzerResultWithinThePayload() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options, processingLimits) -> {
            analysisCalls.incrementAndGet();
            return List.of();
        };
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        String input = "[\"safe\",\"safe\"]";

        try (PrivacySession session = service.openSession()) {
            assertThat(applyOutputPolicy(
                    service,
                    session.handle(),
                    input,
                    PrivacyOutputAction.TOKENIZE
            )).isEqualTo(input);
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
            public List<PiiSpan> analyze(
                    String text,
                    PiiAnalysisOptions options,
                    PrivacyProcessingLimits limits
            ) {
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
            String first = applyOutputPolicy(
                    service,
                    session.handle(),
                    "Alice",
                    PrivacyOutputAction.REDACT
            );
            String second = applyOutputPolicy(
                    service,
                    session.handle(),
                    first,
                    PrivacyOutputAction.REDACT
            );

            assertThat(first).isEqualTo("[REDACTED_PERSON]");
            assertThat(second)
                    .isNotEqualTo(first)
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
                String result = applyOutputPolicy(
                        service,
                        session.handle(),
                        malformed,
                        action
                );
                assertThat(result).isEqualTo(malformed);

            }
        }
    }

    private String applyOutputPolicy(
            PrivacyService service,
            PrivacyContextHandle handle,
            String text,
            PrivacyOutputAction action
    ) {
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service, action, "Blocked output");
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        ChatClientResponse response = new ChatClientResponse(chatResponse, Map.of());
        ChatClientResponse protectedResponse = advisor.protectAtApplicationBoundary(handle, response);
        return protectedResponse.chatResponse().getResult().getOutput().getText();
    }

    private String restoreWithoutTokens(String value, PrivacyProcessingLimits limits) {
        PrivacyService service = new PrivacyService(List.of(), PiiAnalysisOptions.defaults(), limits);
        try (PrivacySession session = service.openSession()) {
            return PrivacyJsonPayloadTransformer.restoreKnownTokens(
                    service, session.handle(), value, PrivacyPhase.OUTPUT_POLICY);
        }
    }

    private void assertPayloadLimit(ThrowingCallable invocation) {
        assertThatThrownBy(invocation)
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.OUTPUT_POLICY);
                    assertThat(failure).hasMessage("Privacy payload exceeded a configured processing limit");
                });
    }
}
