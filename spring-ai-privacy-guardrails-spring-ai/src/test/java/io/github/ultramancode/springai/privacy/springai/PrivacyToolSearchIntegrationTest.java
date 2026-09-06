package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivacyToolSearchIntegrationTest {

    @Test
    void protectsModelGeneratedSearchArgumentsWithoutSecurity() {
        verifyProtectedLoop(false, ResponseMode.MESSAGE);
    }

    @Test
    void protectsStreamingSearchArgumentsWithoutSecurity() {
        verifyProtectedLoop(true, ResponseMode.MESSAGE);
    }

    @Test
    void protectsSearchArgumentsFromStreamingResponseMetadata() {
        verifyProtectedLoop(true, ResponseMode.METADATA);
    }

    @Test
    void rejectsIncompleteSearchArgumentsBeforeTheIndexIsCalled() {
        for (boolean streaming : List.of(false, true)) {
            Scenario scenario = scenario(ResponseMode.INCOMPLETE_ARGUMENTS);

            assertThatThrownBy(() -> invoke(scenario.client(), streaming))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("Structured JSON payload is invalid")
                    .hasMessageNotContaining("Alice");
            verify(scenario.index(), never()).search(any());
            assertThat(scenario.businessInput()).hasValue(null);
            assertThat(scenario.service().activeSessionCount()).isZero();
        }
    }

    @Test
    void streamsTextBeforeCompletionAndCleansUpOnCancellation() {
        Scenario scenario = scenario(ResponseMode.LIVE_TEXT);

        String first = request(scenario.client()).stream().content().next().block(Duration.ofSeconds(5));

        assertThat(first).isEqualTo("early text");
        verify(scenario.index(), never()).search(any());
        assertThat(scenario.service().activeSessionCount()).isZero();
    }

    private void verifyProtectedLoop(boolean streaming, ResponseMode mode) {
        Scenario scenario = scenario(mode);

        assertThat(invoke(scenario.client(), streaming)).isEqualTo("done");

        ArgumentCaptor<ToolSearchRequest> search = ArgumentCaptor.forClass(ToolSearchRequest.class);
        verify(scenario.index()).search(search.capture());
        assertThat(search.getValue().sessionId()).isEqualTo("privacy-search-session");
        assertThat(search.getValue().query()).doesNotContain("Alice");
        assertThat(OpaquePiiTokenFormat.patternForEntityType("PERSON")
                .matcher(search.getValue().query()).find()).isTrue();
        // The ordinary tool's disclosure policy still restores the allowed original.
        assertThat(scenario.businessInput()).hasValue("{\"name\":\"Alice\"}");
        assertThat(scenario.service().activeSessionCount()).isZero();
    }

    private String invoke(ChatClient client, boolean streaming) {
        ChatClient.ChatClientRequestSpec request = request(client);
        return streaming
                ? request.stream().content().collectList()
                        .map(parts -> String.join("", parts)).block(Duration.ofSeconds(5))
                : request.call().content();
    }

    private ChatClient.ChatClientRequestSpec request(ChatClient client) {
        return client.prompt()
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "privacy-search-session"))
                // The raw PII below originates in the test model's response.
                .user("Find a customer lookup tool");
    }

    private Scenario scenario(ResponseMode mode) {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service, ToolDisclosurePolicy.byToolName(Map.of("customerLookup", Set.of("PERSON")))
        );
        AtomicReference<String> businessInput = new AtomicReference<>();
        ToolCallback business = factory.wrap(new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("customerLookup").description("Find a customer")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public String call(String input) {
                businessInput.set(input);
                return "found";
            }
        });
        ToolIndex index = mock(ToolIndex.class);
        when(index.search(any())).thenReturn(ToolSearchResponse.builder()
                .addToolReference(ToolReference.builder().toolName("customerLookup")
                        .summary("Find a customer").build())
                .build());
        ToolCallingManager manager = ToolCallingManager.builder().build();
        ToolSearchToolCallingAdvisor search = ToolSearchToolCallingAdvisor.builder()
                .toolIndex(index).systemMessageSuffix("Search for tools before using them.")
                .toolCallingManager(manager).build();
        ChatClient client = ChatClient.builder(new SearchModel(manager, mode))
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service, factory),
                        search,
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service, factory)
                )
                .defaultTools(business)
                .build();
        return new Scenario(service, index, client, businessInput);
    }

    private record Scenario(
            PrivacyService service,
            ToolIndex index,
            ChatClient client,
            AtomicReference<String> businessInput
    ) {
    }

    private enum ResponseMode {
        MESSAGE, METADATA, INCOMPLETE_ARGUMENTS, LIVE_TEXT
    }

    private static final class SearchModel implements ChatModel {

        private final ToolCallingManager manager;
        private final ResponseMode mode;
        private ToolCallback toolSearchCallback;

        private SearchModel(ToolCallingManager manager, ResponseMode mode) {
            this.manager = manager;
            this.mode = mode;
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            List<ToolDefinition> definitions = this.manager.resolveToolDefinitions(options);
            ToolCallback callback = options.getToolCallbacks().stream()
                    .filter(tool -> tool.getToolDefinition().name().equals("toolSearchTool"))
                    .findFirst().orElseThrow();
            if (this.toolSearchCallback == null) {
                this.toolSearchCallback = callback;
            }
            assertThat(callback).isSameAs(this.toolSearchCallback);
            if (hasResponse(prompt, "customerLookup")) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }
            if (hasResponse(prompt, "toolSearchTool")) {
                return toolCall("customerLookup", "{\"name\":\"Alice\"}", false);
            }
            String schema = definitions.stream().filter(definition -> definition.name().equals("toolSearchTool"))
                    .findFirst().orElseThrow().inputSchema();
            String queryParameter = schema.contains("\"query\"") ? "query" : "arg0";
            String arguments = "{\"" + queryParameter + "\":\"Find Alice\"}";
            if (this.mode == ResponseMode.INCOMPLETE_ARGUMENTS) {
                arguments = arguments.substring(0, arguments.length() - 2);
            }
            return toolCall("toolSearchTool", arguments, this.mode == ResponseMode.METADATA);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return this.mode == ResponseMode.LIVE_TEXT
                    ? Flux.concat(Flux.just(new ChatResponse(List.of(
                            new Generation(new AssistantMessage("early text"))))), Flux.never())
                    : Flux.defer(() -> Flux.just(call(prompt)));
        }

        private static boolean hasResponse(Prompt prompt, String name) {
            return prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                    .anyMatch(response -> name.equals(response.name()));
        }

        private static ChatResponse toolCall(String name, String arguments, boolean metadataChannel) {
            AssistantMessage.ToolCall call = new AssistantMessage.ToolCall("call-" + name, "function", name, arguments);
            AssistantMessage message = AssistantMessage.builder().content("")
                    .toolCalls(metadataChannel ? List.of() : List.of(call)).build();
            ChatResponseMetadata metadata = metadataChannel
                    ? ChatResponseMetadata.builder().keyValue("toolCalls", List.of(call)).build()
                    : ChatResponseMetadata.builder().build();
            return new ChatResponse(List.of(new Generation(message)), metadata);
        }
    }
}
