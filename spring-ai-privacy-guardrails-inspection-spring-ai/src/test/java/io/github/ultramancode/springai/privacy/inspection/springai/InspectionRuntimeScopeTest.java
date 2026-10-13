package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionRuntimeScopeTest {
    static InspectionService attackBlockingService() {
        InspectionRule rule = InspectionRule.literal("attack", InspectionFinding.Category.PROMPT_ATTACK, "attack");
        RuleBasedContentInspector inspector = new RuleBasedContentInspector("rules", List.of(rule));
        return new InspectionService(List.of(inspector));
    }

    private String invoke(ChatClient client, Message message, boolean streaming) {
        Prompt prompt = new Prompt(List.of(new UserMessage("hello"), message));
        ChatClient.ChatClientRequestSpec request = client.prompt(prompt);
        if (streaming) {
            List<String> parts = request.stream().content().collectList().block(Duration.ofSeconds(5));
            return String.join("", parts);
        }
        return request.call().content();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inspectsSupportedRuntimePayloadsAndPreservesExcludedFields(boolean streaming) {
        List<AssistantMessage> messages = List.of(
                AssistantMessage.builder().content("attack").build(),
                AssistantMessage.builder().content("safe").properties(Map.of("thinking", "attack")).build(),
                AssistantMessage.builder().content("safe").properties(Map.of("reasoningContent", "attack")).build(),
                DeepSeekAssistantMessage.builder().content("safe").reasoningContent("attack").build(),
                AssistantMessage.builder().content("safe").toolCalls(List.of(
                        new AssistantMessage.ToolCall("id", "function", "tool", "{\"q\":\"attack\"}"))).build(),
                AssistantMessage.builder().content("safe").toolCalls(List.of(
                        new AssistantMessage.ToolCall("id", "function", "tool", "{\"attack\":\"safe\"}"))).build());
        InspectionChatClientIntegrationTest.RecordingModel model = new InspectionChatClientIntegrationTest.RecordingModel();
        ChatClient client = new InspectionChatClientConfigurer(attackBlockingService()).configure(ChatClient.builder(model)).build();
        for (AssistantMessage message : messages) {
            assertThatThrownBy(() -> invoke(client, message, streaming)).isInstanceOf(InspectionBlockedException.class);
        }
        assertThat(model.modelCallCount).hasValue(0);
        AssistantMessage allowedAssistantMessage = DeepSeekAssistantMessage.builder().content("safe").reasoningContent("ordinary")
                .prefix(true).properties(Map.of("opaque", "attack", "thinking", Map.of("value", "attack")))
                .toolCalls(List.of(new AssistantMessage.ToolCall("attack", "attack", "attack", "{\"q\":\"safe\"}")))
                .build();
        assertThat(invoke(client, allowedAssistantMessage, streaming)).isEqualTo("done");
        // Compare delivered messages against an ordinary client, including the supported Spring AI 2.0.0 baseline.
        // That version already loses DeepSeek-specific fields while copying the prompt.
        InspectionChatClientIntegrationTest.RecordingModel plainModel = new InspectionChatClientIntegrationTest.RecordingModel();
        invoke(ChatClient.builder(plainModel).build(), allowedAssistantMessage, streaming);
        assertThat(model.receivedPrompt.get().getInstructions())
                .containsExactlyElementsOf(plainModel.receivedPrompt.get().getInstructions());
    }

    @Test
    void inputPayloadsPreserveJsonStructureEscapesAndNumericLexemes() {
        InspectionTextExtractor inputTextExtractor = new InspectionTextExtractor(InspectionLimits.defaults(),
                ContentSegment.PrivacyProcessingStatus.UNKNOWN, "test-");
        String jsonPayload = "{\"attack\":\"safe\",\"dup\":\"at\\u0074ack\",\"dup\":42,\"n\":1e999999,\"b\":true,\"z\":null}";
        inputTextExtractor.addMessage(new UserMessage(jsonPayload));
        inputTextExtractor.addMessage(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("attack", "attack", "[\"one\",{\"attack\":\"two\"}]"))).build());
        inputTextExtractor.addMessage(new UserMessage("{malformed attack"));
        inputTextExtractor.addMessage(new UserMessage("\"first\" \"attack\""));
        assertThat(inputTextExtractor.buildSegments()).extracting(ContentSegment::text).containsExactly(
                jsonPayload, "[\"one\",{\"attack\":\"two\"}]", "{malformed attack", "\"first\" \"attack\"");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inputRegexCanMatchJsonKeysAndStructure(boolean streaming) {
        InspectionRule rule = InspectionRule.regex("action", InspectionFinding.Category.PROMPT_ATTACK,
                "^\\{\\s*\"action\"\\s*:\\s*\"attack\"\\s*\\}$");
        InspectionService inspectionService = new InspectionService(List.of(
                new RuleBasedContentInspector("rules", List.of(rule))));
        InspectionChatClientIntegrationTest.RecordingModel model =
                new InspectionChatClientIntegrationTest.RecordingModel();
        ChatClient client = new InspectionChatClientConfigurer(inspectionService).configure(ChatClient.builder(model)).build();

        assertThat(invoke(client, new UserMessage("{\"note\":\"attack\"}"), streaming)).isEqualTo("done");
        assertThatThrownBy(() -> invoke(client, new UserMessage("{\n  \"action\": \"attack\"\n}"), streaming))
                .isInstanceOf(InspectionBlockedException.class);
        assertThat(model.modelCallCount).hasValue(1);
    }

    @Test
    void extractionBudgetsIncludeSerializedPayloadsAndEmptyFields() {
        InspectionLimits limits = new InspectionLimits(2, 20, Duration.ofSeconds(2));
        InspectionTextExtractor inputTextExtractor = new InspectionTextExtractor(limits, ContentSegment.PrivacyProcessingStatus.UNKNOWN, "test-");
        assertThatThrownBy(() -> inputTextExtractor.addMessage(new UserMessage("{\"" + "key".repeat(10) + "\":0}")))
                .hasMessageContaining("LIMIT_EXCEEDED");
        InspectionTextExtractor emptyPayloadExtractor = new InspectionTextExtractor(limits, ContentSegment.PrivacyProcessingStatus.UNKNOWN, "test-");
        emptyPayloadExtractor.addMessage(AssistantMessage.builder().content("").properties(Map.of("thinking", "")).build());
        assertThatThrownBy(() -> emptyPayloadExtractor.addMessage(new UserMessage(""))).hasMessageContaining("LIMIT_EXCEEDED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inspectsDeepSeekToolArgumentsBeforeNextModelCall(boolean streaming) {
        for (boolean attackInToolArguments : List.of(false, true)) {
            AtomicInteger modelCallCount = new AtomicInteger();
            AtomicInteger toolCallCount = new AtomicInteger();
            String toolArgumentValue = "safe";
            if (attackInToolArguments) {
                toolArgumentValue = "attack";
            }
            DeepSeekAssistantMessage toolCallMessage = DeepSeekAssistantMessage.builder()
                    .content("").reasoningContent("planning").toolCalls(List.of(new AssistantMessage.ToolCall(
                            "id", "function", "lookup", "{\"q\":\"" + toolArgumentValue + "\"}"))).build();
            // Spring AI 2.0.0 Prompt.copy() loses provider fields. Match the upstream history representation.
            Message expectedHistoryMessage = new Prompt(toolCallMessage).copy().getInstructions().get(0);
            PrivacyService privacyService = new PrivacyService(List.of((text, options, limits) -> List.of()), PiiAnalysisOptions.defaults());
            ChatModel model = new ChatModel() {
                public ChatOptions getOptions() {
                    return ToolCallingChatOptions.builder().build();
                }

                public ChatResponse call(Prompt prompt) {
                    if (modelCallCount.incrementAndGet() == 1) {
                        return new ChatResponse(List.of(new Generation(toolCallMessage)));
                    }
                    if (!streaming) {
                        assertThat(prompt.getInstructions()).filteredOn(AssistantMessage.class::isInstance)
                                .singleElement().isExactlyInstanceOf(expectedHistoryMessage.getClass())
                                .isEqualTo(expectedHistoryMessage);
                    }
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                }

                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.defer(() -> Flux.just(call(prompt)));
                }
            };
            ToolCallback lookupToolCallback = new ToolCallback() {
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name("lookup").description("attack")
                            .inputSchema("{\"type\":\"object\",\"description\":\"attack\"}").build();
                }

                public String call(String arguments) {
                    toolCallCount.incrementAndGet();
                    return "safe";
                }
            };
            ChatClient client = ModelRequestBoundaryConfigurer.compose(new PrivacyChatClientConfigurer(privacyService),
                    new InspectionChatClientConfigurer(attackBlockingService())).configure(ChatClient.builder(model))
                    .defaultTools(new PrivacyToolCallbackFactory(privacyService, ToolDisclosurePolicy.denyAll()).wrap(lookupToolCallback)).build();
            if (attackInToolArguments) {
                assertThatThrownBy(() -> invoke(client, new UserMessage("hello"), streaming))
                        .isInstanceOf(InspectionBlockedException.class);
                assertThat(modelCallCount).hasValue(1);
            } else {
                assertThat(invoke(client, new UserMessage("hello"), streaming)).isEqualTo("done");
                assertThat(modelCallCount).hasValue(2);
            }
            // Input inspection acts before the next model call. Output inspection is a separate opt-in.
            assertThat(toolCallCount).hasValue(1);
            assertThat(privacyService.activeSessionCount()).isZero();
        }
    }
}
