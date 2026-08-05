package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyToolCallbackWrapperTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void scopedToolDetokenizesJsonArgumentsAndRetokenizesResultInSameSession() {
        PrivacyService service = TestPrivacyServices.privacyService();
        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), "Alice");
            ToolCallback delegate = delegate(input -> {
                assertThat(input).isEqualTo("{\"name\":\"Alice\"}");
                return "Bob found Alice";
            });
            PrivacyToolCallbackWrapper wrapper = wrap(
                    delegate,
                    service,
                    ToolDisclosurePolicy.byToolName(Map.of("lookup", Set.of("PERSON")))
            );

            String result = wrapper.call("{\"name\":\"" + token + "\"}", toolContext(session.handle()));

            assertThat(result).doesNotContain("Alice", "Bob");
            assertThat(service.detokenize(session.handle(), result)).isEqualTo("Bob found Alice");
        }
    }

    @Test
    void toolResultTokenizesNumericJsonScalarWithoutBreakingJson() throws Exception {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> "821012345678"),
                service
        );

        try (PrivacySession session = service.openSession()) {
            String result = wrapper.call("{}", toolContext(session.handle()));
            Object protectedResult = OBJECT_MAPPER.readValue(result, Object.class);

            assertThat(protectedResult)
                    .isInstanceOf(String.class)
                    .asString()
                    .containsPattern(io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat
                            .patternForEntityType("PHONE_NUMBER"))
                    .doesNotContain("821012345678");
            assertThat(service.detokenizeValueTree(session.handle(), protectedResult))
                    .isEqualTo(821012345678L);
        }
    }

    @Test
    void toolResultRecursivelyTokenizesNestedJsonObjectsAndArrays() throws Exception {
        PrivacyService service = TestPrivacyServices.privacyService();
        String rawResult = "{\"phone\":821012345678,\"nested\":{\"owners\":[\"Alice\",821012345678],"
                + "\"email\":\"alice\\u0040example.com\"}}";
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> rawResult),
                service
        );

        try (PrivacySession session = service.openSession()) {
            String result = wrapper.call("{}", toolContext(session.handle()));
            Object protectedResult = OBJECT_MAPPER.readValue(result, Object.class);

            assertThat(result).doesNotContain(
                    "Alice",
                    "821012345678",
                    "alice@example.com",
                    "alice\\u0040example.com"
            );
            assertThat(service.detokenizeValueTree(session.handle(), protectedResult))
                    .isEqualTo(OBJECT_MAPPER.readValue(rawResult, Object.class));
        }
    }

    @Test
    void toolResultPreservesUntouchedNumericLexemesAndTokenizesExponentPii() {
        PrivacyService service = TestPrivacyServices.privacyService();
        String rawResult = "{\"precise\":0.1234567890123456789012345,"
                + "\"scientific\":1e3,\"phone\":8.21012345678e11}";
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> rawResult),
                service
        );

        try (PrivacySession session = service.openSession()) {
            String result = wrapper.call("{}", toolContext(session.handle()));

            assertThat(result)
                    .contains("\"precise\":0.1234567890123456789012345")
                    .contains("\"scientific\":1e3")
                    .doesNotContain("8.21012345678e11", "821012345678")
                    .containsPattern(io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat
                            .patternForEntityType("PHONE_NUMBER"));
        }
    }

    @Test
    void scopedToolRestoresAllowedExponentNumberWithItsInputLexeme() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> received = new AtomicReference<>();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    received.set(input);
                    return "ok";
                }),
                service,
                ToolDisclosurePolicy.byToolName(Map.of("lookup", List.of("PHONE_NUMBER")))
        );

        try (PrivacySession session = service.openSession()) {
            wrapper.call("{\"phone\":8.21012345678e11}", toolContext(session.handle()));
        }

        assertThat(received.get()).isEqualTo("{\"phone\":8.21012345678e11}");
    }

    @Test
    void toolResultTreatsMalformedOrBracketShapedContentAsPlainText() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> delegateResult = new AtomicReference<>();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> delegateResult.get()),
                service
        );

        try (PrivacySession session = service.openSession()) {
            for (String plainText : List.of(
                    "{\"owner\":\"Alice\"",
                    "[INFO] Alice is available",
                    "{not-json Bob is available"
            )) {
                delegateResult.set(plainText);
                String protectedPlainText = wrapper.call("{}", toolContext(session.handle()));

                assertThat(protectedPlainText).doesNotContain("Alice", "Bob");
                assertThat(service.detokenize(session.handle(), protectedPlainText))
                        .isEqualTo(plainText);
            }
        }
    }

    @Test
    void toolInputAndResultRejectOversizedStructuredScalarsWithSafeTypedFailure() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicInteger delegateCalls = new AtomicInteger();
        AtomicReference<String> delegateResult = new AtomicReference<>("ok");
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    delegateCalls.incrementAndGet();
                    return delegateResult.get();
                }),
                service
        );
        String oversized = "{\"value\":\""
                + "x".repeat(PrivacyJsonPayloadTransformer.MAX_STRING_SCALAR_CHARACTERS + 1)
                + "\"}";

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> wrapper.call(oversized, toolContext(session.handle())))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOOL_INPUT);
                    });
            assertThat(delegateCalls).hasValue(0);

            delegateResult.set(oversized);
            assertThatThrownBy(() -> wrapper.call("{}", toolContext(session.handle())))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOOL_OUTPUT);
                    });
            assertThat(delegateCalls).hasValue(1);
        }
    }

    @Test
    void toolInputAnalyzesManyUniqueScalarsInOneCharacterBoundedBatch() {
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
        AtomicReference<String> received = new AtomicReference<>();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    received.set(input);
                    return " ";
                }),
                service
        );
        String input = IntStream.range(0, 600)
                .mapToObj(index -> "\"secret-" + index + "\"")
                .collect(Collectors.joining(",", "[", "]"));

        try (PrivacySession session = service.openSession()) {
            wrapper.call(input, toolContext(session.handle()));

            assertThat(received.get()).doesNotContain("secret-");
            assertThat(service.detokenize(session.handle(), received.get())).isEqualTo(input);
        }
        assertThat(analysisCalls).hasValue(1);
    }

    @Test
    void toolInputRejectsNonblankMalformedJsonBeforeDelegateExecution() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicInteger delegateCalls = new AtomicInteger();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    delegateCalls.incrementAndGet();
                    return "ok";
                }),
                service
        );

        try (PrivacySession session = service.openSession()) {
            String ownedToken = service.tokenize(session.handle(), "Alice");
            String callerSuppliedLookalike =
                    "[[PII_PERSON_0123456789abcdef0123456789abcdef_1]]";
            for (String invalidJson : List.of(
                    "{\"owner\":\"Alice\"",
                    "not-json",
                    ownedToken,
                    ownedToken + " trailing",
                    callerSuppliedLookalike,
                    callerSuppliedLookalike + " trailing"
            )) {
                assertThatThrownBy(() -> wrapper.call(
                        invalidJson,
                        toolContext(session.handle())
                )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOOL_INPUT);
                    assertThat(failure).hasMessageNotContaining("Alice");
                });
            }
        }

        assertThat(delegateCalls).hasValue(0);
    }

    @Test
    void toolInputAcceptsValidJsonRootPrimitives() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> received = new AtomicReference<>();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    received.set(input);
                    return "ok";
                }),
                service
        );

        try (PrivacySession session = service.openSession()) {
            for (String validJson : List.of("\"safe\"", "42")) {
                wrapper.call(validJson, toolContext(session.handle()));
                assertThat(received).hasValue(validJson);
            }
        }
    }

    @Test
    void scopedToolReceivesOnlyItsAllowedEntityTypesAsOriginals() {
        PrivacyService service = TestPrivacyServices.privacyService();
        try (PrivacySession session = service.openSession()) {
            String personToken = service.tokenize(session.handle(), "Alice");
            String email = "alice@example.com";
            String emailToken = service.tokenize(
                    session.handle(),
                    email,
                    List.of(new io.github.ultramancode.springai.privacy.core.PiiSpan(
                            "EMAIL_ADDRESS",
                            0,
                            email.length(),
                            0.95
                    ))
            );
            AtomicReference<String> received = new AtomicReference<>();
            PrivacyToolCallbackWrapper wrapper = wrap(
                    delegate(input -> {
                        received.set(input);
                        return "Alice " + email;
                    }),
                    service,
                    ToolDisclosurePolicy.byToolName(Map.of(
                            "lookup",
                            Set.of("EMAIL_ADDRESS")
                    ))
            );

            String result = wrapper.call(
                    "{\"name\":\"" + personToken + "\",\"email\":\"" + emailToken + "\"}",
                    toolContext(session.handle())
            );

            assertThat(received.get())
                    .contains(email)
                    .contains(personToken)
                    .doesNotContain("Alice");
            assertThat(result)
                    .doesNotContain("Alice", email)
                    .containsPattern(io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat
                            .patternForEntityType("PERSON"))
                    .containsPattern(io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat
                            .patternForEntityType("EMAIL_ADDRESS"));
        }
    }

    @Test
    void scopedToolProtectsRawDisallowedValuesBeforeSelectiveDisclosure() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> received = new AtomicReference<>();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    received.set(input);
                    return "ok";
                }),
                service,
                ToolDisclosurePolicy.byToolName(Map.of(
                        "lookup",
                        Set.of("EMAIL_ADDRESS")
                ))
        );

        try (PrivacySession session = service.openSession()) {
            wrapper.call(
                    "{\"name\":\"Alice\",\"email\":\"alice@example.com\"}",
                    toolContext(session.handle())
            );

            assertThat(received.get())
                    .contains("alice@example.com")
                    .containsPattern(io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat
                            .patternForEntityType("PERSON"))
                    .doesNotContain("Alice");
        }
    }

    @Test
    void unscopedToolTokenizesRawNestedJsonArgumentsByDefault() {
        PrivacyService service = TestPrivacyServices.privacyService();
        try (PrivacySession session = service.openSession()) {
            AtomicReference<String> received = new AtomicReference<>();
            ToolCallback delegate = delegate(input -> {
                received.set(input);
                return "ok";
            });
            String rawInput = "{\"name\":\"Alice\",\"nested\":{\"owners\":[\"Bob\",7]}}";

            wrap(delegate, service).call(
                    rawInput,
                    toolContext(session.handle())
            );

            assertThat(received.get()).doesNotContain("Alice", "Bob");
            assertThat(service.detokenize(session.handle(), received.get())).isEqualTo(rawInput);
        }
    }

    @Test
    void unscopedToolTokenizesNumericPiiInsteadOfPassingTheJsonNumberThrough() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> received = new AtomicReference<>();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    received.set(input);
                    return "ok";
                }),
                service
        );

        try (PrivacySession session = service.openSession()) {
            wrapper.call("{\"phone\":821012345678}", toolContext(session.handle()));

            assertThat(received.get())
                    .doesNotContain("821012345678")
                    .containsPattern(io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat
                            .patternForEntityType("PHONE_NUMBER"));
        }
    }

    @Test
    void scopedToolRestoresAllowedNumericPiiWithoutChangingItsJsonType() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> received = new AtomicReference<>();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    received.set(input);
                    return "ok";
                }),
                service,
                ToolDisclosurePolicy.byToolName(Map.of("lookup", List.of("PHONE_NUMBER")))
        );

        try (PrivacySession session = service.openSession()) {
            wrapper.call("{\"phone\":821012345678}", toolContext(session.handle()));

            assertThat(received.get()).isEqualTo("{\"phone\":821012345678}");
        }
    }

    @Test
    void unscopedToolTokenizesRawJsonArgumentsByDefault() {
        PrivacyService service = TestPrivacyServices.privacyService();
        try (PrivacySession session = service.openSession()) {
            AtomicReference<String> received = new AtomicReference<>();
            PrivacyToolCallbackWrapper wrapper = wrap(
                    delegate(input -> {
                        received.set(input);
                        return "ok";
                    }),
                    service
            );

            wrapper.call("{\"query\":\"lookup Alice\"}", toolContext(session.handle()));

            assertThat(received.get()).doesNotContain("Alice");
            assertThat(service.detokenize(session.handle(), received.get()))
                    .isEqualTo("{\"query\":\"lookup Alice\"}");
        }
    }

    @Test
    void scopedToolContextSurvivesVirtualThreadTransitionWithoutThreadLocal() throws Exception {
        PrivacyService service = TestPrivacyServices.privacyService();
        try (PrivacySession session = service.openSession();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            String token = service.tokenize(session.handle(), "Alice");
            PrivacyToolCallbackWrapper wrapper = wrap(
                    delegate(input -> {
                        assertThat(input).isEqualTo("{\"query\":\"lookup Alice\"}");
                        return "Alice found";
                    }),
                    service,
                    ToolDisclosurePolicy.byToolName(Map.of("lookup", Set.of("PERSON")))
            );

            String result = CompletableFuture.supplyAsync(
                    () -> wrapper.call(
                            "{\"query\":\"lookup " + token + "\"}",
                            toolContext(session.handle())
                    ),
                    executor
            ).get();

            assertThat(service.detokenize(session.handle(), result)).isEqualTo("Alice found");
        }
    }

    @Test
    void toolWithoutSessionFailsClosedBeforeDelegateExecution() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> received = new AtomicReference<>();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> {
                    received.set(input);
                    return "Alice found";
                }),
                service
        );

        assertThatThrownBy(() -> wrapper.call("{}"))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("active privacy session");
        assertThat(received.get()).isNull();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void nullToolResultFailsClosedInsteadOfBecomingAnEmptyModelResponse() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackWrapper wrapper = wrap(
                delegate(input -> null),
                service
        );

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> wrapper.call("{}", toolContext(session.handle())))
                    .isInstanceOf(ToolExecutionException.class)
                    .hasMessage("Tool execution failed")
                    .hasCauseInstanceOf(PrivacyGuardrailException.class);
        }
    }

    private ToolContext toolContext(PrivacyContextHandle handle) {
        return new ToolContext(Map.of(PrivacyRequestContextSupport.CONTEXT_HANDLE, handle));
    }

    private PrivacyToolCallbackWrapper wrap(ToolCallback delegate, PrivacyService service) {
        return wrap(delegate, service, ToolDisclosurePolicy.denyAll());
    }

    private PrivacyToolCallbackWrapper wrap(
            ToolCallback delegate,
            PrivacyService service,
            ToolDisclosurePolicy policy
    ) {
        return (PrivacyToolCallbackWrapper) new PrivacyToolCallbackFactory(service, policy).wrap(delegate);
    }

    private ToolCallback delegate(Callback callback) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("lookup")
                        .description("lookup")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return callback.call(toolInput);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return callback.call(toolInput);
            }
        };
    }

    @FunctionalInterface
    private interface Callback {
        String call(String input);
    }
}
