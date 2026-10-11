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
        assertThat(model.calls).hasValue(0);
        AssistantMessage allowed = DeepSeekAssistantMessage.builder().content("safe").reasoningContent("ordinary")
                .prefix(true).properties(Map.of("opaque", "attack", "thinking", Map.of("value", "attack")))
                .toolCalls(List.of(new AssistantMessage.ToolCall("attack", "attack", "attack", "{\"q\":\"safe\"}")))
                .build();
        assertThat(invoke(client, allowed, streaming)).isEqualTo("done");
        // Compare delivered messages against an ordinary client, including the supported Spring AI 2.0.0 baseline.
        // That version already loses DeepSeek-specific fields while copying the prompt.
        InspectionChatClientIntegrationTest.RecordingModel plainModel = new InspectionChatClientIntegrationTest.RecordingModel();
        invoke(ChatClient.builder(plainModel).build(), allowed, streaming);
        assertThat(model.receivedPrompt.get().getInstructions())
                .containsExactlyElementsOf(plainModel.receivedPrompt.get().getInstructions());
    }

    @Test
    void inputPayloadsPreserveJsonStructureEscapesAndNumericLexemes() {
        InspectionTextExtractor extractor = new InspectionTextExtractor(InspectionLimits.defaults(),
                ContentSegment.PrivacyProcessingStatus.UNKNOWN, "test-");
        String json = "{\"attack\":\"safe\",\"dup\":\"at\\u0074ack\",\"dup\":42,\"n\":1e999999,\"b\":true,\"z\":null}";
        extractor.message(new UserMessage(json));
        extractor.message(ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("attack", "attack", "[\"one\",{\"attack\":\"two\"}]"))).build());
        extractor.message(new UserMessage("{malformed attack"));
        extractor.message(new UserMessage("\"first\" \"attack\""));
        assertThat(extractor.segments()).extracting(ContentSegment::text).containsExactly(
                json, "[\"one\",{\"attack\":\"two\"}]", "{malformed attack", "\"first\" \"attack\"");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inputRegexCanMatchJsonKeysAndStructure(boolean streaming) {
        InspectionRule rule = InspectionRule.regex("action", InspectionFinding.Category.PROMPT_ATTACK,
                "^\\{\\s*\"action\"\\s*:\\s*\"attack\"\\s*\\}$");
        InspectionService inspection = new InspectionService(List.of(
                new RuleBasedContentInspector("rules", List.of(rule))));
        InspectionChatClientIntegrationTest.RecordingModel model =
                new InspectionChatClientIntegrationTest.RecordingModel();
        ChatClient client = new InspectionChatClientConfigurer(inspection).configure(ChatClient.builder(model)).build();

        assertThat(invoke(client, new UserMessage("{\"note\":\"attack\"}"), streaming)).isEqualTo("done");
        assertThatThrownBy(() -> invoke(client, new UserMessage("{\n  \"action\": \"attack\"\n}"), streaming))
                .isInstanceOf(InspectionBlockedException.class);
        assertThat(model.calls).hasValue(1);
    }

    @Test
    void extractionBudgetsIncludeSerializedPayloadsAndEmptyFields() {
        InspectionLimits limits = new InspectionLimits(2, 20, Duration.ofSeconds(2));
        InspectionTextExtractor extractor = new InspectionTextExtractor(limits, ContentSegment.PrivacyProcessingStatus.UNKNOWN, "test-");
        assertThatThrownBy(() -> extractor.message(new UserMessage("{\"" + "key".repeat(10) + "\":0}")))
                .hasMessageContaining("LIMIT_EXCEEDED");
        InspectionTextExtractor empty = new InspectionTextExtractor(limits, ContentSegment.PrivacyProcessingStatus.UNKNOWN, "test-");
        empty.message(AssistantMessage.builder().content("").properties(Map.of("thinking", "")).build());
        assertThatThrownBy(() -> empty.message(new UserMessage(""))).hasMessageContaining("LIMIT_EXCEEDED");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deepSeekToolLoopWorksWithPrivacyAndReinspectsArguments(boolean streaming) {
        for (boolean attack : List.of(false, true)) {
            AtomicInteger calls = new AtomicInteger();
            AtomicInteger toolCallCount = new AtomicInteger();
            String argument = "safe";
            if (attack) {
                argument = "attack";
            }
            DeepSeekAssistantMessage toolCallMessage = DeepSeekAssistantMessage.builder()
                    .content("").reasoningContent("planning").toolCalls(List.of(new AssistantMessage.ToolCall(
                            "id", "function", "lookup", "{\"q\":\"" + argument + "\"}"))).build();
            // Spring AI 2.0.0 Prompt.copy() loses provider fields. Match the upstream history representation.
            Message expectedHistoryMessage = new Prompt(toolCallMessage).copy().getInstructions().get(0);
            PrivacyService privacy = new PrivacyService(List.of((text, options, limits) -> List.of()), PiiAnalysisOptions.defaults());
            ChatModel model = new ChatModel() {
                public ChatOptions getOptions() {
                    return ToolCallingChatOptions.builder().build();
                }

                public ChatResponse call(Prompt prompt) {
                    if (calls.incrementAndGet() == 1) {
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
            ToolCallback callback = new ToolCallback() {
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name("lookup").description("attack")
                            .inputSchema("{\"type\":\"object\",\"description\":\"attack\"}").build();
                }

                public String call(String arguments) {
                    toolCallCount.incrementAndGet();
                    return "safe";
                }
            };
            ChatClient client = ModelRequestBoundaryConfigurer.compose(new PrivacyChatClientConfigurer(privacy),
                    new InspectionChatClientConfigurer(attackBlockingService())).configure(ChatClient.builder(model))
                    .defaultTools(new PrivacyToolCallbackFactory(privacy, ToolDisclosurePolicy.denyAll()).wrap(callback)).build();
            if (attack) {
                assertThatThrownBy(() -> invoke(client, new UserMessage("hello"), streaming))
                        .isInstanceOf(InspectionBlockedException.class);
                assertThat(calls).hasValue(1);
            } else {
                assertThat(invoke(client, new UserMessage("hello"), streaming)).isEqualTo("done");
                assertThat(calls).hasValue(2);
            }
            // Input inspection acts before the next model call. Output inspection is a separate opt-in.
            assertThat(toolCallCount).hasValue(1);
            assertThat(privacy.activeSessionCount()).isZero();
        }
    }
}
