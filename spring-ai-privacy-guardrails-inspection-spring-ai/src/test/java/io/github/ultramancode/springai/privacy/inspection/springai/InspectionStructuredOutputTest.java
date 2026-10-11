package io.github.ultramancode.springai.privacy.inspection.springai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.JsonField;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.models.FunctionDefinition;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ultramancode.springai.privacy.inspection.springai.InspectionRuntimeScopeTest.attackBlockingService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InspectionStructuredOutputTest {
    record Answer(boolean ok) {}

    @Test
    void preservesOutputFormatsAndToolDefinitionsInOpenAiRequests() {
        ChatCompletionMessage message = ChatCompletionMessage.builder()
                .content("{\"ok\":true}").refusal(Optional.empty()).build();
        ChatCompletion.Choice choice = ChatCompletion.Choice.builder()
                .index(0).finishReason(ChatCompletion.Choice.FinishReason.STOP)
                .message(message).logprobs(Optional.empty()).build();
        ChatCompletion completion = ChatCompletion.builder()
                .id("fixture").model("fixture").created(0).choices(List.of(choice)).build();
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ChatService chatService = mock(ChatService.class);
        ChatCompletionService completions = mock(ChatCompletionService.class);
        when(openAiClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(completions);
        // Spring AI 2.0.0 uses the overload that supplies default request options.
        when(completions.create(any(ChatCompletionCreateParams.class))).thenCallRealMethod();
        AtomicReference<ChatCompletionCreateParams> capturedRequest = new AtomicReference<>();
        when(completions.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenAnswer(invocation -> {
                    capturedRequest.set(invocation.getArgument(0));
                    return completion;
                });
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder().model("fixture").build())
                .openAiClient(openAiClient).openAiClientAsync(mock(OpenAIClientAsync.class)).build();
        ChatClient client = new InspectionChatClientConfigurer(attackBlockingService())
                .withOutputInspection(new InspectionOutputAdvisor(attackBlockingService()))
                .configure(ChatClient.builder(model)).build();
        assertThat(client.prompt().user("hello").call().entity(Answer.class)).isEqualTo(new Answer(true));
        assertThat(capturedRequest.get().messages()).singleElement().satisfies(userMessage ->
                assertThat(userMessage.asUser().content().asText()).contains("\"ok\""));
        assertThat(client.prompt().user("hello").call().entity(Answer.class, p -> p.useProviderStructuredOutput()))
                .isEqualTo(new Answer(true));
        ResponseFormatJsonSchema.JsonSchema.Schema generatedSchema = capturedRequest.get().responseFormat()
                .orElseThrow().asJsonSchema().jsonSchema().schema().orElseThrow();
        JsonField<?> generatedProperties = generatedSchema._additionalProperties().get("properties");
        assertThat(generatedProperties.asObject().orElseThrow()).containsKey("ok");
        String schema = "{\"type\":\"object\",\"description\":\"attack\",\"properties\":{\"ok\":{\"type\":\"boolean\"}}}";
        assertThat(client.prompt().user("hello").options(OpenAiChatOptions.builder().outputSchema(schema))
                .call().content()).isEqualTo("{\"ok\":true}");
        ResponseFormatJsonSchema.JsonSchema.Schema customSchema = capturedRequest.get().responseFormat()
                .orElseThrow().asJsonSchema().jsonSchema().schema().orElseThrow();
        assertThat(customSchema._additionalProperties()).containsEntry("description", JsonValue.from("attack"));
        client.prompt().user("hello").advisors(a -> a.param(ChatClientAttributes.OUTPUT_FORMAT.getKey(), "attack"))
                .call().content();
        assertThat(capturedRequest.get().messages()).singleElement().satisfies(userMessage ->
                assertThat(userMessage.asUser().content().asText()).contains("attack"));

        ToolCallback tool = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("attack").inputSchema(schema).build();
            }

            public String call(String arguments) {
                throw new AssertionError("No tool execution expected");
            }
        };
        ToolCallbackProvider provider = () -> new ToolCallback[] {tool};
        client.prompt().user("hello").tools(provider).call().content();
        assertThat(capturedRequest.get().tools().orElseThrow()).singleElement().satisfies(requestTool -> {
            FunctionDefinition function = requestTool.asFunction().function();
            assertThat(function.name()).isEqualTo("lookup");
            assertThat(function.description()).contains("attack");
            assertThat(function.parameters().orElseThrow()._additionalProperties())
                    .containsEntry("description", JsonValue.from("attack"));
        });
        verify(completions, times(5)).create(any(ChatCompletionCreateParams.class), any(RequestOptions.class));
    }
}
