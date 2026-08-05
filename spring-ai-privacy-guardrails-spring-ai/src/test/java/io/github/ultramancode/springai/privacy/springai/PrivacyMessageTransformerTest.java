package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyMessageTransformerTest {

    @Test
    void tokenizeProtectsStructuredAssistantAndToolHistoryWithoutChangingSafeNumericLexemes() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyMessageTransformer transformer = new PrivacyMessageTransformer(service);
        String precise = "0.1234567890123456789012345";
        String scientific = "1e3";
        AssistantMessage assistant = AssistantMessage.builder()
                .content("{\"email\":\"alice\\u0040example.com\",\"precise\":" + precise + "}")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "1",
                        "function",
                        "lookup",
                        "{\"owner\":\"Alice\",\"revision\":" + scientific + "}"
                )))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "1",
                        "lookup",
                        "{\"nested\":[\"Bob\",{\"email\":\"alice\\u0040example.com\"}],"
                                + "\"precise\":" + precise + "}"
                )))
                .build();

        try (PrivacySession session = service.openSession()) {
            List<Message> protectedMessages = transformer.tokenize(
                    session.handle(),
                    List.of(assistant, toolResponse)
            );
            AssistantMessage protectedAssistant = (AssistantMessage) protectedMessages.get(0);
            ToolResponseMessage protectedToolResponse = (ToolResponseMessage) protectedMessages.get(1);

            assertThat(protectedAssistant.getText())
                    .doesNotContain("alice@example.com", "alice\\u0040example.com")
                    .contains("\"precise\":" + precise);
            assertThat(service.detokenize(session.handle(), protectedAssistant.getText()))
                    .contains("alice@example.com");
            assertThat(protectedAssistant.getToolCalls().get(0).arguments())
                    .doesNotContain("Alice")
                    .contains("\"revision\":" + scientific);
            assertThat(protectedToolResponse.getResponses().get(0).responseData())
                    .doesNotContain("Bob", "alice@example.com", "alice\\u0040example.com")
                    .contains("\"precise\":" + precise);
        }
    }

    @Test
    void tokenizeProtectsKnownProviderTextMetadataAndPreservesOpaqueSignatures() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyMessageTransformer transformer = new PrivacyMessageTransformer(service);
        List<byte[]> thoughtSignatures = List.of(new byte[]{1, 2, 3});
        AssistantMessage assistant = AssistantMessage.builder()
                .content("safe")
                .properties(Map.of(
                        "reasoningContent", "Alice reviewed the request",
                        "thinking", "Contact alice@example.com",
                        "thoughtSignatures", thoughtSignatures,
                        "signature", "opaque-provider-signature"
                ))
                .build();

        try (PrivacySession session = service.openSession()) {
            AssistantMessage protectedAssistant = (AssistantMessage) transformer.tokenize(
                    session.handle(),
                    List.of(assistant)
            ).get(0);

            assertThat(protectedAssistant.getMetadata().get("reasoningContent").toString())
                    .doesNotContain("Alice")
                    .contains("[[PII_PERSON_");
            assertThat(protectedAssistant.getMetadata().get("thinking").toString())
                    .doesNotContain("alice@example.com")
                    .contains("[[PII_EMAIL_ADDRESS_");
            assertThat(protectedAssistant.getMetadata().get("thoughtSignatures"))
                    .isSameAs(thoughtSignatures);
            assertThat(protectedAssistant.getMetadata().get("signature"))
                    .isEqualTo("opaque-provider-signature");
        }
    }

    @Test
    void tokenizeRejectsNonblankMalformedJsonToolCallArguments() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyMessageTransformer transformer = new PrivacyMessageTransformer(service);

        try (PrivacySession session = service.openSession()) {
            for (String invalidArguments : List.of(
                    "{\"owner\":\"Alice\"",
                    "not-json"
            )) {
                AssistantMessage assistant = AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "1",
                                "function",
                                "lookup",
                                invalidArguments
                        )))
                        .build();

                assertThatThrownBy(() -> transformer.tokenize(session.handle(), List.of(assistant)))
                        .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                            assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                            assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOKENIZATION);
                            assertThat(failure).hasMessageNotContaining("Alice");
                        });
            }
        }
    }

    @Test
    void tokenizeTreatsBracketShapedToolResponseHistoryAsPlainText() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyMessageTransformer transformer = new PrivacyMessageTransformer(service);
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(
                        new ToolResponseMessage.ToolResponse(
                                "1",
                                "lookup",
                                "[INFO] Alice is available"
                        ),
                        new ToolResponseMessage.ToolResponse(
                                "2",
                                "lookup",
                                "{not-json Bob is available"
                        )
                ))
                .build();

        try (PrivacySession session = service.openSession()) {
            ToolResponseMessage protectedResponse = (ToolResponseMessage) transformer.tokenize(
                    session.handle(),
                    List.of(toolResponse)
            ).get(0);

            assertThat(protectedResponse.getResponses())
                    .extracting(ToolResponseMessage.ToolResponse::responseData)
                    .allSatisfy(responseData -> assertThat(responseData)
                            .doesNotContain("Alice", "Bob")
                            .contains("[[PII_PERSON_"));
        }
    }

    @Test
    void tokenizeTreatsUnicodeEscapeInOrdinaryMessageAsPlainText() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyMessageTransformer transformer = new PrivacyMessageTransformer(service);
        UserMessage message = new UserMessage("Java source can contain \\u0041 without being JSON");

        try (PrivacySession session = service.openSession()) {
            assertThat(transformer.tokenize(session.handle(), List.of(message)))
                    .containsExactly(message);
        }
    }

    @Test
    void tokenizeRejectsOversizedOrdinaryMessageBeforeAnalysis() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyMessageTransformer transformer = new PrivacyMessageTransformer(service);
        UserMessage message = new UserMessage(
                "x".repeat(PrivacyJsonPayloadTransformer.MAX_PAYLOAD_CHARACTERS + 1)
        );

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> transformer.tokenize(session.handle(), List.of(message)))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOKENIZATION);
                    });
        }
    }

    @Test
    void tokenizeFailsClosedForUnsupportedMessageWithText() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyMessageTransformer transformer = new PrivacyMessageTransformer(service);

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> transformer.tokenize(
                    session.handle(),
                    List.of(new CustomMessage("Alice must not pass through"))
            ))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessageContaining("Unsupported Spring AI Message implementation")
                    .hasMessageNotContaining("Alice");
        }
    }

    @Test
    void tokenizeFailsClosedForUnsupportedMessageEvenWhenTextIsBlank() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyMessageTransformer transformer = new PrivacyMessageTransformer(service);

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> transformer.tokenize(
                    session.handle(),
                    List.of(new CustomMessage(""))
            ))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessageContaining("Unsupported Spring AI Message implementation");
        }
    }

    @Test
    void tokenizeFailsClosedForProviderSpecificAssistantMessageSubtype() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyMessageTransformer transformer = new PrivacyMessageTransformer(service);
        AssistantMessage providerMessage = new ProviderAssistantMessage("safe", "Alice is hidden");

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> transformer.tokenize(session.handle(), List.of(providerMessage)))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOKENIZATION);
                        assertThat(failure).hasMessage("Unsupported Spring AI Message implementation")
                                .hasMessageNotContaining("Alice");
                    });
        }
    }

    @Test
    void tokenizeFailsClosedForUserMessageSubtypeWithHiddenText() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyMessageTransformer transformer = new PrivacyMessageTransformer(service);
        UserMessage providerMessage = new ProviderUserMessage("safe", "Alice is hidden");

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> transformer.tokenize(session.handle(), List.of(providerMessage)))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOKENIZATION);
                        assertThat(failure).hasMessage("Unsupported Spring AI Message implementation")
                                .hasMessageNotContaining("Alice");
                    });
        }
    }

    @Test
    void outputTransformFailsClosedForProviderSpecificAssistantMessageSubtype() {
        AssistantMessage providerMessage = new ProviderAssistantMessage("safe", "Alice is hidden");

        assertThatThrownBy(() -> PrivacyMessageTransformer.transformAssistantMessage(
                providerMessage,
                text -> text
        ))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                    assertThat(failure.phase()).isEqualTo(PrivacyPhase.OUTPUT_POLICY);
                    assertThat(failure).hasMessage("Unsupported Spring AI Message implementation")
                            .hasMessageNotContaining("Alice");
                });
    }

    @Test
    void tokenizeProtectsOfficialDeepSeekAssistantMessageWithoutDroppingProviderFields() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyMessageTransformer transformer = new PrivacyMessageTransformer(service);
        DeepSeekAssistantMessage message = new DeepSeekAssistantMessage.Builder()
                .content("safe")
                .reasoningContent("Alice considered the request")
                .prefix(true)
                .properties(Map.of("provider", "deepseek"))
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "lookup",
                        "{\"owner\":\"Alice\"}"
                )))
                .build();

        try (PrivacySession session = service.openSession()) {
            DeepSeekAssistantMessage protectedMessage = (DeepSeekAssistantMessage) transformer.tokenize(
                    session.handle(),
                    List.of(message)
            ).get(0);

            assertThat(protectedMessage.getText()).isEqualTo("safe");
            assertThat(protectedMessage.getReasoningContent())
                    .doesNotContain("Alice")
                    .contains("[[PII_PERSON_");
            assertThat(protectedMessage.getPrefix()).isTrue();
            assertThat(protectedMessage.getMetadata()).containsEntry("provider", "deepseek");
            assertThat(protectedMessage.getToolCalls().get(0).arguments())
                    .doesNotContain("Alice")
                    .contains("[[PII_PERSON_");
        }
    }

    @Test
    void outputTransformProtectsOfficialDeepSeekReasoningContent() {
        DeepSeekAssistantMessage message = new DeepSeekAssistantMessage.Builder()
                .content("Alice answered")
                .reasoningContent("Alice reasoned")
                .prefix(true)
                .build();

        DeepSeekAssistantMessage transformed = (DeepSeekAssistantMessage)
                PrivacyMessageTransformer.transformAssistantMessage(
                        message,
                        text -> text.replace("Alice", "protected")
                );

        assertThat(transformed.getText()).isEqualTo("protected answered");
        assertThat(transformed.getReasoningContent()).isEqualTo("protected reasoned");
        assertThat(transformed.getPrefix()).isTrue();
    }

    private record CustomMessage(String text) implements Message {

        @Override
        public String getText() {
            return this.text;
        }

        @Override
        public Map<String, Object> getMetadata() {
            return Map.of();
        }

        @Override
        public MessageType getMessageType() {
            return MessageType.USER;
        }
    }

    private static final class ProviderAssistantMessage extends AssistantMessage {

        @SuppressWarnings("unused")
        private final String hiddenReasoning;

        private ProviderAssistantMessage(String text, String hiddenReasoning) {
            super(text, Map.of(), List.of(), List.of());
            this.hiddenReasoning = hiddenReasoning;
        }
    }

    private static final class ProviderUserMessage extends UserMessage {

        @SuppressWarnings("unused")
        private final String hiddenText;

        private ProviderUserMessage(String text, String hiddenText) {
            super(text);
            this.hiddenText = hiddenText;
        }
    }
}
