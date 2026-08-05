package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyReturnDirectIntegrationTest {

    private static final Pattern PERSON_TOKEN = OpaquePiiTokenFormat.patternForEntityType("PERSON");

    @Test
    void returnDirectToolFailureRetainsHostExceptionSemantics() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> toolInput = new AtomicReference<>();
        VerifyingToolLoopModel model = new VerifyingToolLoopModel();
        ToolCallback scopedTool = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.byToolName(Map.of("customerLookup", Set.of("PERSON")))
        ).wrap(failingReturnDirectTool(toolInput));
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .defaultTools(scopedTool)
                .build();

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Lookup failed for Alice");
        assertThat(toolInput.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(model.calls()).isEqualTo(1);
        assertThat(model.rawPiiSeenByModel()).isFalse();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void returnDirectToolResultObeysTokenizeOutputPolicy() {
        PrivacyService service = TestPrivacyServices.privacyService();
        String result = returnDirectChatClient(service, PrivacyOutputAction.TOKENIZE)
                .prompt().user("Find Alice").call().content();

        assertThat(result).matches(PERSON_TOKEN.pattern() + " direct result")
                .doesNotContain("Alice");
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void returnDirectToolResultObeysRedactOutputPolicy() {
        PrivacyService service = TestPrivacyServices.privacyService();
        String result = returnDirectChatClient(service, PrivacyOutputAction.REDACT)
                .prompt().user("Find Alice").call().content();

        assertThat(result).isEqualTo("[REDACTED_PERSON] direct result");
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void returnDirectToolResultObeysBlockOutputPolicy() {
        PrivacyService service = TestPrivacyServices.privacyService();
        ChatClient chatClient = returnDirectChatClient(service, PrivacyOutputAction.BLOCK);

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
                .isInstanceOf(PrivacyOutputBlockedException.class)
                .hasMessage("blocked");
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void streamingReturnDirectToolResultObeysBlockOutputPolicy() {
        PrivacyService service = TestPrivacyServices.privacyService();
        ChatClient chatClient = returnDirectChatClient(service, PrivacyOutputAction.BLOCK);

        assertThatThrownBy(() -> streamContent(chatClient))
                .isInstanceOf(PrivacyOutputBlockedException.class)
                .hasMessage("blocked");
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void normalToolSelectionContinuesTheLoopWhenMixedReturnDirectToolsAreRegistered() {
        PrivacyService service = TestPrivacyServices.privacyService();
        SelectedToolBatchModel model = new SelectedToolBatchModel(List.of("search"));
        ChatClient chatClient = mixedReturnDirectChatClient(service, model);

        String result = chatClient.prompt().user("Search").call().content();

        assertThat(result).isEqualTo("Completed");
        assertThat(model.calls()).isEqualTo(2);
        assertThat(model.receivedOnlyTokenizedToolResults()).isTrue();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void mixedToolSelectionUsesSpringAiBatchDecisionAndRetokenizesEveryResult() {
        PrivacyService service = TestPrivacyServices.privacyService();
        SelectedToolBatchModel model = new SelectedToolBatchModel(
                List.of("search", "downloadReceipt")
        );
        ChatClient chatClient = mixedReturnDirectChatClient(service, model);

        String result = chatClient.prompt().user("Run both").call().content();

        assertThat(result).isEqualTo("Completed");
        assertThat(model.calls()).isEqualTo(2);
        assertThat(model.receivedOnlyTokenizedToolResults()).isTrue();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void allDirectToolSelectionReturnsImmediatelyWhenMixedMetadataIsRegistered() {
        PrivacyService service = TestPrivacyServices.privacyService();
        SelectedToolBatchModel model = new SelectedToolBatchModel(
                List.of("downloadReceipt", "downloadArchive")
        );
        ChatClient chatClient = mixedReturnDirectChatClient(service, model);

        String result = chatClient.prompt().user("Download both").call().content();

        assertThat(result).isEqualTo("[REDACTED_PERSON] receipt result");
        assertThat(model.calls()).isEqualTo(1);
        assertThat(service.activeSessionCount()).isZero();
    }

    private ChatClient returnDirectChatClient(PrivacyService service, PrivacyOutputAction action) {
        AtomicReference<String> toolInput = new AtomicReference<>();
        VerifyingToolLoopModel model = new VerifyingToolLoopModel();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.byToolName(Map.of("customerLookup", Set.of("PERSON")))
        );
        ToolCallback scopedTool = factory.wrap(returnDirectTool(toolInput));
        ToolCallback normalTool = factory.wrap(tool("search", false, "Bob search result"));
        return ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyOutputAdvisor(service, action, "blocked"),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .defaultTools(scopedTool, normalTool)
                .build();
    }

    private ChatClient mixedReturnDirectChatClient(
            PrivacyService service,
            SelectedToolBatchModel model
    ) {
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        return ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyOutputAdvisor(service, PrivacyOutputAction.REDACT, "blocked"),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .defaultTools(
                        factory.wrap(tool("search", false, "Alice search result")),
                        factory.wrap(tool("downloadReceipt", true, "Bob receipt result")),
                        factory.wrap(tool("downloadArchive", true, "Alice archive result"))
                )
                .build();
    }

    private String streamContent(ChatClient chatClient) {
        return chatClient.prompt()
                .user("Find Alice")
                .stream()
                .content()
                .collectList()
                .map(parts -> String.join("", parts))
                .block();
    }

    private ToolCallback failingReturnDirectTool(AtomicReference<String> toolInput) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("customerLookup")
                        .description("Looks up a customer")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(true).build();
            }

            @Override
            public String call(String input) {
                toolInput.set(input);
                throw new IllegalStateException("Lookup failed for Alice");
            }
        };
    }

    private ToolCallback returnDirectTool(AtomicReference<String> toolInput) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("customerLookup")
                        .description("Looks up a customer")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(true).build();
            }

            @Override
            public String call(String input) {
                toolInput.set(input);
                return "Alice direct result";
            }
        };
    }

    private ToolCallback tool(String name, boolean returnDirect, String result) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(returnDirect).build();
            }

            @Override
            public String call(String input) {
                return result;
            }
        };
    }

    private static final class VerifyingToolLoopModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean rawPiiSeenByModel;

        private final boolean toolOptionsByDefault;

        private VerifyingToolLoopModel() {
            this(true);
        }

        private VerifyingToolLoopModel(boolean toolOptionsByDefault) {
            this.toolOptionsByDefault = toolOptionsByDefault;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            int call = this.calls.incrementAndGet();
            this.rawPiiSeenByModel = this.rawPiiSeenByModel || prompt.getInstructions().stream()
                    .map(Message::getText)
                    .anyMatch(text -> text != null && (text.contains("Alice") || text.contains("Bob")));
            if (call == 1) {
                String token = prompt.getInstructions().stream()
                        .map(Message::getText)
                        .map(PERSON_TOKEN::matcher)
                        .filter(Matcher::find)
                        .map(Matcher::group)
                        .findFirst()
                        .orElseThrow();
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "customerLookup",
                                "{\"name\":\"" + token + "\"}"
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }

            ToolResponseMessage toolResponse = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .findFirst()
                    .orElseThrow();
            String responseData = toolResponse.getResponses().get(0).responseData();
            if (responseData.contains("Bob")) {
                this.rawPiiSeenByModel = true;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Customer Bob is active"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return this.toolOptionsByDefault
                    ? ToolCallingChatOptions.builder().build()
                    : ChatOptions.builder().build();
        }

        int calls() {
            return this.calls.get();
        }

        boolean rawPiiSeenByModel() {
            return this.rawPiiSeenByModel;
        }
    }

    private static final class SelectedToolBatchModel implements ChatModel {

        private final List<String> selectedToolNames;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean receivedOnlyTokenizedToolResults;

        private SelectedToolBatchModel(List<String> selectedToolNames) {
            this.selectedToolNames = List.copyOf(selectedToolNames);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            int call = this.calls.incrementAndGet();
            if (call == 1) {
                List<AssistantMessage.ToolCall> toolCalls = this.selectedToolNames.stream()
                        .map(name -> new AssistantMessage.ToolCall(
                                "call-" + name,
                                "function",
                                name,
                                "{}"
                        ))
                        .toList();
                return new ChatResponse(List.of(new Generation(
                        AssistantMessage.builder().content("").toolCalls(toolCalls).build()
                )));
            }

            ToolResponseMessage toolResponse = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .findFirst()
                    .orElseThrow();
            this.receivedOnlyTokenizedToolResults = toolResponse.getResponses().size()
                    == this.selectedToolNames.size()
                    && toolResponse.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .allMatch(responseData -> PERSON_TOKEN.matcher(responseData).find()
                            && !responseData.contains("Alice")
                            && !responseData.contains("Bob")
                            && !responseData.contains("[REDACTED_"));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Completed"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        int calls() {
            return this.calls.get();
        }

        boolean receivedOnlyTokenizedToolResults() {
            return this.receivedOnlyTokenizedToolResults;
        }
    }

}
