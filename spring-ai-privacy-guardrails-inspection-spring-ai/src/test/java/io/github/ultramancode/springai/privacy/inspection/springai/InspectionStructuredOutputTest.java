package io.github.ultramancode.springai.privacy.inspection.springai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import reactor.core.publisher.Flux;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionStructuredOutputTest {
    record Answer(boolean ok) {}

    @Test
    void springAiEntityAndNativeSchemaReachActualOpenAiRequestConstruction() throws Exception {
        var options = OpenAiChatOptions.builder().model("fixture").build();
        var serializer = OpenAiChatModel.builder().options(options)
                .openAiClient(unusedClient(OpenAIClient.class)).openAiClientAsync(unusedClient(OpenAIClientAsync.class)).build();
        var buildPrompt = OpenAiChatModel.class.getDeclaredMethod("buildRequestPrompt", Prompt.class);
        var createRequest = OpenAiChatModel.class.getDeclaredMethod("createRequest", Prompt.class, boolean.class);
        buildPrompt.setAccessible(true);
        createRequest.setAccessible(true);
        AtomicReference<String> wire = new AtomicReference<>();
        var model = new ChatModel() {
            public ChatOptions getOptions() {
                return options;
            }

            public ChatResponse call(Prompt prompt) {
                try {
                    wire.set(createRequest.invoke(serializer, buildPrompt.invoke(serializer, prompt), false).toString());
                } catch (ReflectiveOperationException ex) {
                    throw new AssertionError(ex);
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"ok\":true}"))));
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> Flux.just(call(prompt)));
            }
        };
        var client = new InspectionChatClientConfigurer(InspectionRuntimeScopeTest.service())
                .withOutputInspection(new InspectionOutputAdvisor(InspectionRuntimeScopeTest.service()))
                .configure(ChatClient.builder(model)).build();
        assertThat(client.prompt().user("hello").call().entity(Answer.class)).isEqualTo(new Answer(true));
        assertThat(wire.get()).contains("ok");
        assertThat(client.prompt().user("hello").call().entity(Answer.class, p -> p.useProviderStructuredOutput()))
                .isEqualTo(new Answer(true));
        assertThat(wire.get()).contains("json_schema");
        String schema = "{\"type\":\"object\",\"description\":\"attack\",\"properties\":{\"ok\":{\"type\":\"boolean\"}}}";
        assertThat(client.prompt().user("hello").options(OpenAiChatOptions.builder().outputSchema(schema))
                .call().content()).contains("ok");
        assertThat(wire.get()).contains("attack", "json_schema");
        client.prompt().user("hello").advisors(a -> a.param(ChatClientAttributes.OUTPUT_FORMAT.getKey(), "attack"))
                .call().content();
        assertThat(wire.get()).contains("attack");

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
        assertThat(wire.get()).contains("attack", "lookup");
    }

    private static <T> T unusedClient(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            throw new AssertionError("No network call expected");
        }));
    }
}
