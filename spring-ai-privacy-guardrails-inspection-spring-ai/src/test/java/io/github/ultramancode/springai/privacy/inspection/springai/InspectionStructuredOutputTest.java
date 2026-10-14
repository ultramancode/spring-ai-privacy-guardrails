package io.github.ultramancode.springai.privacy.inspection.springai;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;

import java.util.List;
import java.util.Optional;

import static io.github.ultramancode.springai.privacy.inspection.springai.InspectionTestSupport.attackBlockingService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InspectionStructuredOutputTest {
    private static final String RESPONSE_JSON = """
            {"ok":true}
            """.strip();

    private ChatClient client;
    private ChatCompletionService chatCompletionService;

    record Answer(boolean ok) {}

    @BeforeEach
    void setUpClientWithInputAndOutputInspection() {
        ChatCompletionMessage message = ChatCompletionMessage.builder()
                .content(RESPONSE_JSON).refusal(Optional.empty()).build();
        ChatCompletion.Choice choice = ChatCompletion.Choice.builder()
                .index(0).finishReason(ChatCompletion.Choice.FinishReason.STOP)
                .message(message).logprobs(Optional.empty()).build();
        ChatCompletion completion = ChatCompletion.builder()
                .id("fixture").model("fixture").created(0).choices(List.of(choice)).build();
        OpenAIClient openAiClient = mock(OpenAIClient.class);
        ChatService chatService = mock(ChatService.class);
        chatCompletionService = mock(ChatCompletionService.class);
        when(openAiClient.chat()).thenReturn(chatService);
        when(chatService.completions()).thenReturn(chatCompletionService);
        // Execute the SDK default method used by Spring AI 2.0.0.
        // It delegates create(request) to create(request, RequestOptions.none()).
        when(chatCompletionService.create(any(ChatCompletionCreateParams.class))).thenCallRealMethod();
        when(chatCompletionService.create(any(ChatCompletionCreateParams.class), any(RequestOptions.class)))
                .thenReturn(completion);
        OpenAiChatModel model = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder().model("fixture").build())
                .openAiClient(openAiClient).openAiClientAsync(mock(OpenAIClientAsync.class)).build();
        client = new InspectionChatClientConfigurer(attackBlockingService())
                .withOutputInspection(new InspectionOutputAdvisor(attackBlockingService()))
                .configure(ChatClient.builder(model)).build();
    }

    @Test
    void preservesPromptBasedOutputFormatAndEntityConversion() {
        assertThat(client.prompt().user("hello").call().entity(Answer.class)).isEqualTo(new Answer(true));

        assertThat(singleOpenAiRequest().messages()).singleElement().satisfies(userMessage ->
                assertThat(userMessage.asUser().content().asText()).contains("""
                        "ok"
                        """.strip()));
    }

    @Test
    void preservesProviderStructuredOutputAndEntityConversion() {
        assertThat(client.prompt().user("hello").call().entity(Answer.class, p -> p.useProviderStructuredOutput()))
                .isEqualTo(new Answer(true));

        assertThat(singleOpenAiRequest().responseFormat().orElseThrow().isJsonSchema()).isTrue();
    }

    @Test
    void preservesCustomOutputSchemaWithoutInspectingItsDescription() {
        String outputSchemaJson = """
                {
                  "type": "object",
                  "description": "attack",
                  "properties": {
                    "ok": {
                      "type": "boolean"
                    }
                  }
                }
                """;
        assertThat(client.prompt().user("hello")
                .options(OpenAiChatOptions.builder().outputSchema(outputSchemaJson))
                .call().content()).isEqualTo(RESPONSE_JSON);

        ResponseFormatJsonSchema.JsonSchema.Schema customSchema = singleOpenAiRequest().responseFormat()
                .orElseThrow().asJsonSchema().jsonSchema().schema().orElseThrow();
        assertThat(customSchema._additionalProperties()).containsEntry("description", JsonValue.from("attack"));
    }

    private ChatCompletionCreateParams singleOpenAiRequest() {
        ArgumentCaptor<ChatCompletionCreateParams> requestCaptor =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(chatCompletionService).create(requestCaptor.capture(), any(RequestOptions.class));
        return requestCaptor.getValue();
    }
}
