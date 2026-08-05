package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.StructuredOutputConverter;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyChatClientIntegrationTest {

    private static final Pattern PERSON_TOKEN = OpaquePiiTokenFormat.patternForEntityType("PERSON");

    @Test
    void unchangedProtectedPromptIsSafelyReanalyzedAtTheFinalModelBoundary() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            int start = text.indexOf("Alice");
            return start < 0
                    ? List.of()
                    : List.of(new PiiSpan("PERSON", start, start + 5, 0.95));
        };
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.defaults()
        );
        RecordingPromptModel model = new RecordingPromptModel();
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .build();

        String result = chatClient.prompt().user("Find Alice").call().content();

        assertThat(result).isEqualTo("ok");
        assertThat(model.lastPrompt())
                .containsPattern(OpaquePiiTokenFormat.patternForEntityType("PERSON"))
                .doesNotContain("Alice");
        assertThat(analysisCalls.get()).isEqualTo(2);
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void structuredOutputFormatAddedBySpringIsRejectedBeforeTheTerminalModelAdvisor() {
        PrivacyService service = TestPrivacyServices.privacyService();
        RecordingPromptModel model = new RecordingPromptModel();
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .build();
        StructuredOutputConverter<String> converter = new StructuredOutputConverter<>() {
            @Override
            public String getFormat() {
                return "Return the record for Alice";
            }

            @Override
            public String convert(String source) {
                return source;
            }
        };

        assertThatThrownBy(() -> chatClient.prompt().user("hello").call().entity(converter))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessage("Terminal model augmentation rejected by privacy guardrail")
                .hasMessageNotContaining("Alice");
        assertThat(model.lastPrompt()).isNull();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void partialProviderStreamFailureIsPreservedAndCleansTheLifecycleSession() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PartialFailureModel model = new PartialFailureModel();
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyOutputAdvisor(service),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .build();

        assertThatThrownBy(() -> chatClient.prompt()
                .user("Find Alice")
                .stream()
                .content()
                .collectList()
                .block())
                .isSameAs(model.failure());
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void chatClientProtectsRagToolAndOutputBoundariesEndToEnd() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> toolInput = new AtomicReference<>();
        VerifyingToolLoopModel model = new VerifyingToolLoopModel();
        ToolCallback scopedTool = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.byToolName(Map.of("customerLookup", Set.of("PERSON")))
        ).wrap(lookupTool(toolInput));
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        ragAdvisor(),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service),
                        new PrivacyOutputAdvisor(service, PrivacyOutputAction.TOKENIZE, "blocked")
                )
                .defaultTools(scopedTool)
                .build();

        String result = chatClient.prompt().user("Find Alice").call().content();

        assertThat(model.optionsType()).contains("ToolCallingChatOptions");
        assertThat(model.calls()).isEqualTo(2);
        assertThat(toolInput.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(model.rawPiiSeenByModel()).isFalse();
        assertThat(result).matches("Customer " + PERSON_TOKEN.pattern() + " is active")
                .doesNotContain("Bob");
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void protectedToolCallbackProvidersRefreshBetweenRequestsAndKeepExactDisclosure() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> customerInput = new AtomicReference<>();
        AtomicReference<String> searchInput = new AtomicReference<>();
        AtomicReference<String> auditInput = new AtomicReference<>();
        AtomicReference<ToolCallback[]> currentTools = new AtomicReference<>(new ToolCallback[]{
                recordingTool("customerLookup", customerInput)
        });
        AtomicInteger providerResolutions = new AtomicInteger();
        AtomicInteger supplementalResolutions = new AtomicInteger();
        ToolCallbackProvider sourceProvider = () -> {
            providerResolutions.incrementAndGet();
            return currentTools.get();
        };
        ToolCallbackProvider supplementalProvider = () -> {
            supplementalResolutions.incrementAndGet();
            return new ToolCallback[]{recordingTool("auditTrail", auditInput)};
        };
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.byToolName(Map.of("customerLookup", Set.of("PERSON")))
        );
        ProviderToolLoopModel model = new ProviderToolLoopModel();
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .defaultTools(factory.wrapProviders(sourceProvider, supplementalProvider))
                .build();

        assertThat(chatClient.prompt().user("Find Alice").call().content()).isEqualTo("ok");
        assertThat(service.activeSessionCount()).isZero();

        currentTools.set(new ToolCallback[]{recordingTool("knowledgeSearch", searchInput)});
        assertThat(chatClient.prompt().user("Find Alice").call().content()).isEqualTo("ok");

        assertThat(providerResolutions).hasValue(2);
        assertThat(supplementalResolutions).hasValue(2);
        assertThat(model.registeredToolNames()).containsExactly(
                List.of("customerLookup", "auditTrail"),
                List.of("knowledgeSearch", "auditTrail")
        );
        assertThat(customerInput.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(searchInput.get())
                .containsPattern(PERSON_TOKEN)
                .doesNotContain("Alice");
        assertThat(auditInput.get()).isNull();
        assertThat(model.rawPiiSeenByModel()).isFalse();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void configuredToolCallingAdvisorOrderRemainsInsidePrivacyBoundary() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> toolInput = new AtomicReference<>();
        VerifyingToolLoopModel model = new VerifyingToolLoopModel();
        ToolCallback scopedTool = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.byToolName(Map.of("customerLookup", Set.of("PERSON")))
        ).wrap(lookupTool(toolInput));
        ToolCallingAdvisor customToolAdvisor = ToolCallingAdvisor.builder()
                .advisorOrder(0)
                .build();
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service),
                        customToolAdvisor,
                        new PrivacyToolCallValidationAdvisor(service, customToolAdvisor.getOrder() + 1),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .defaultTools(scopedTool)
                .build();

        String result = chatClient.prompt().user("Find Alice").call().content();

        assertThat(result).isEqualTo("Customer Bob is active");
        assertThat(toolInput.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(model.calls()).isEqualTo(2);
        assertThat(model.rawPiiSeenByModel()).isFalse();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void streamingChatClientKeepsPrivacySessionAcrossBoundedElasticToolExecution() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> toolInput = new AtomicReference<>();
        VerifyingToolLoopModel model = new VerifyingToolLoopModel();
        ToolCallback scopedTool = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.byToolName(Map.of("customerLookup", Set.of("PERSON")))
        ).wrap(lookupTool(toolInput));
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        ragAdvisor(),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service),
                        new PrivacyOutputAdvisor(service, PrivacyOutputAction.TOKENIZE, "blocked")
                )
                .defaultTools(scopedTool)
                .build();

        String result = chatClient.prompt()
                .user("Find Alice")
                .stream()
                .content()
                .collectList()
                .map(parts -> String.join("", parts))
                .block();

        assertThat(model.calls()).isEqualTo(2);
        assertThat(toolInput.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(model.rawPiiSeenByModel()).isFalse();
        assertThat(result).matches("Customer " + PERSON_TOKEN.pattern() + " is active")
                .doesNotContain("Bob");
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void streamingChatClientRejectsSplitToolNamesBeforeSpringAggregation() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> toolInput = new AtomicReference<>();
        ToolCallback wrappedTool = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        ).wrap(lookupTool(toolInput));
        ChatClient chatClient = ChatClient.builder(new SplitToolNameStreamModel())
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .defaultTools(wrappedTool)
                .build();

        assertThatThrownBy(() -> chatClient.prompt().user("hello").stream().content().collectList().block())
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessage("Model requested a tool outside the registered privacy boundary");
        assertThat(toolInput.get()).isNull();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void toolExecutionBoundaryRejectsResponseMutationBeforeSpringExecutesTool() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> toolInput = new AtomicReference<>();
        ToolCallback wrappedTool = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        ).wrap(lookupTool(toolInput));
        ChatClient chatClient = ChatClient.builder(new SafeToolCapableModel())
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        responseToolCallMutator(),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .defaultTools(wrappedTool)
                .build();

        assertThatThrownBy(() -> chatClient.prompt().user("hello").call().content())
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessage("Tool control field rejected by privacy guardrail")
                .hasMessageNotContaining("Alice");
        assertThat(toolInput.get()).isNull();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void streamingChatClientRejectsMetadataToolCallsBeforeSpringAggregation() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> toolInput = new AtomicReference<>();
        ToolCallback wrappedTool = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        ).wrap(lookupTool(toolInput));
        ChatClient chatClient = ChatClient.builder(new MetadataToolCallStreamModel())
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .defaultTools(wrappedTool)
                .build();

        assertThatThrownBy(() -> chatClient.prompt().user("hello").stream().content().collectList().block())
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessage("Tool control field rejected by privacy guardrail")
                .hasMessageNotContaining("Alice");
        assertThat(toolInput.get()).isNull();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void callLifecycleBoundaryProtectsPiiAddedAfterTheInnerOutputAdvisor() {
        PrivacyService service = TestPrivacyServices.privacyService();
        ChatClient chatClient = ChatClient.builder(new RecordingPromptModel())
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        postOutputPiiAdvisor(),
                        new PrivacyOutputAdvisor(service),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .build();

        String result = chatClient.prompt().user("hello").call().content();

        assertThat(result)
                .matches(PERSON_TOKEN.pattern() + " added after output")
                .doesNotContain("Alice");
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void streamLifecycleBoundaryProtectsPiiAddedAfterTheInnerOutputAdvisor() {
        PrivacyService service = TestPrivacyServices.privacyService();
        ChatClient chatClient = ChatClient.builder(new RecordingPromptModel())
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        postOutputPiiAdvisor(),
                        new PrivacyOutputAdvisor(service),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .build();

        String result = chatClient.prompt().user("hello").stream().content().collectList()
                .map(parts -> String.join("", parts))
                .block();

        assertThat(result)
                .matches(PERSON_TOKEN.pattern() + " added after output")
                .doesNotContain("Alice");
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void streamingToolLoopAppliesFrameLimitBeforeSpringAggregatesIntermediateToolCalls() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> toolInput = new AtomicReference<>();
        ToolCallback wrappedTool = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        ).wrap(lookupTool(toolInput));
        PrivacyResponseInspectionLimits limits = new PrivacyResponseInspectionLimits(
                2,
                1_000,
                1_000,
                Duration.ofSeconds(1)
        );
        ChatClient chatClient = ChatClient.builder(new FloodingToolStreamModel())
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        new PrivacyOutputAdvisor(service, PrivacyOutputAction.TOKENIZE, "blocked", limits),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .defaultTools(wrappedTool)
                .build();

        assertThatThrownBy(() -> chatClient.prompt().user("hello").stream().content().collectList().block())
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessage("Model response exceeded the configured privacy inspection limit");
        assertThat(toolInput.get()).isNull();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void toolContextAdvisorAttachesSessionToToolOptionsInjectedAfterInputAdvisor() {
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<String> toolInput = new AtomicReference<>();
        VerifyingToolLoopModel model = new VerifyingToolLoopModel(false);
        ToolCallback wrappedTool = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.byToolName(Map.of("customerLookup", Set.of("PERSON")))
        ).wrap(lookupTool(toolInput));
        CallAdvisor dynamicTools = dynamicToolsAdvisor(wrappedTool);
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        new PrivacyInputAdvisor(service),
                        dynamicTools,
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .build();

        String result = chatClient.prompt().user("Find Alice").call().content();

        assertThat(result).isEqualTo("Customer Bob is active");
        assertThat(toolInput.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(model.calls()).isEqualTo(2);
        assertThat(model.rawPiiSeenByModel()).isFalse();
        assertThat(service.activeSessionCount()).isZero();
    }

    private CallAdvisor dynamicToolsAdvisor(ToolCallback toolCallback) {
        return new CallAdvisor() {
            @Override
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                        .toolCallbacks(List.of(toolCallback))
                        .build();
                return chain.nextCall(request.mutate()
                        .prompt(new Prompt(request.prompt().getInstructions(), options))
                        .build());
            }

            @Override
            public String getName() {
                return "DynamicToolsAdvisor";
            }

            @Override
            public int getOrder() {
                return PrivacyInputAdvisor.DEFAULT_ORDER + 1;
            }
        };
    }

    private CallAdvisor postOutputPiiAdvisor() {
        class PostOutputPiiAdvisor implements CallAdvisor, StreamAdvisor {
            @Override
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                return addPii(chain.nextCall(request));
            }

            @Override
            public Flux<ChatClientResponse> adviseStream(
                    ChatClientRequest request,
                    StreamAdvisorChain chain
            ) {
                return chain.nextStream(request).map(this::addPii);
            }

            private ChatClientResponse addPii(ChatClientResponse response) {
                return response.mutate()
                        .chatResponse(TestPrivacyServices.response("Alice added after output").chatResponse())
                        .build();
            }

            @Override
            public String getName() {
                return "PostOutputPiiAdvisor";
            }

            @Override
            public int getOrder() {
                return PrivacyOutputAdvisor.DEFAULT_ORDER - 1;
            }
        }
        return new PostOutputPiiAdvisor();
    }

    private CallAdvisor responseToolCallMutator() {
        return new CallAdvisor() {
            @Override
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                ChatClientResponse response = chain.nextCall(request);
                AssistantMessage injected = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1", "function", "Alice", "{}"
                        )))
                        .build();
                return response.mutate()
                        .chatResponse(new ChatResponse(List.of(new Generation(injected))))
                        .build();
            }

            @Override
            public String getName() {
                return "ResponseToolCallMutator";
            }

            @Override
            public int getOrder() {
                return PrivacyToolCallValidationAdvisor.DEFAULT_ORDER + 1;
            }
        };
    }

    private CallAdvisor ragAdvisor() {
        return new CallAdvisor() {
            @Override
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                List<Message> messages = new ArrayList<>(request.prompt().getInstructions());
                messages.add(new org.springframework.ai.chat.messages.UserMessage("Retrieved owner: Alice"));
                return chain.nextCall(request.mutate()
                        .prompt(new Prompt(messages, request.prompt().getOptions()))
                        .build());
            }

            @Override
            public String getName() {
                return "TestRagAdvisor";
            }

            @Override
            public int getOrder() {
                return 0;
            }
        };
    }

    private ToolCallback lookupTool(AtomicReference<String> toolInput) {
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
            public String call(String input) {
                toolInput.set(input);
                return "Customer Bob is active";
            }
        };
    }

    private ToolCallback recordingTool(String name, AtomicReference<String> toolInput) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("Synthetic test tool")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public String call(String input) {
                toolInput.set(input);
                return "Result for Bob";
            }
        };
    }

    private static final class VerifyingToolLoopModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean rawPiiSeenByModel;
        private volatile String optionsType;

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
            this.optionsType = prompt.getOptions() == null ? "null" : prompt.getOptions().getClass().getName();
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

        String optionsType() {
            return this.optionsType;
        }
    }

    private static final class ProviderToolLoopModel implements ChatModel {

        private final List<List<String>> registeredToolNames = new ArrayList<>();
        private boolean rawPiiSeenByModel;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.rawPiiSeenByModel = this.rawPiiSeenByModel || prompt.getInstructions().stream()
                    .map(Message::getText)
                    .anyMatch(text -> text != null && (text.contains("Alice") || text.contains("Bob")));
            Optional<ToolResponseMessage> toolResponse = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .findFirst();
            if (toolResponse.isPresent()) {
                this.rawPiiSeenByModel = this.rawPiiSeenByModel
                        || toolResponse.get().getResponses().stream()
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .anyMatch(value -> value.contains("Alice") || value.contains("Bob"));
                return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
            }

            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            List<ToolCallback> callbacks = options.getToolCallbacks();
            this.registeredToolNames.add(callbacks.stream()
                    .map(callback -> callback.getToolDefinition().name())
                    .toList());
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
                            "call-" + this.registeredToolNames.size(),
                            "function",
                            callbacks.get(0).getToolDefinition().name(),
                            "{\"name\":\"" + token + "\"}"
                    )))
                    .build();
            return new ChatResponse(List.of(new Generation(toolCall)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        List<List<String>> registeredToolNames() {
            return List.copyOf(this.registeredToolNames);
        }

        boolean rawPiiSeenByModel() {
            return this.rawPiiSeenByModel;
        }
    }

    private static final class RecordingPromptModel implements ChatModel {

        private volatile String lastPrompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.lastPrompt = prompt.getInstructions().stream()
                    .map(Message::getText)
                    .filter(Objects::nonNull)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        String lastPrompt() {
            return this.lastPrompt;
        }
    }

    private static final class PartialFailureModel implements ChatModel {

        private final IllegalStateException providerFailure;

        private PartialFailureModel() {
            this.providerFailure = new IllegalStateException("provider response contained Alice");
            this.providerFailure.addSuppressed(new IllegalStateException("Alice suppressed"));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("stream only");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.concat(
                    Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("Alice partial"))))),
                    Flux.error(this.providerFailure)
            );
        }

        private IllegalStateException failure() {
            return this.providerFailure;
        }
    }

    private static final class FloodingToolStreamModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("stream only");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.range(0, Integer.MAX_VALUE).map(index -> {
                AssistantMessage message = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-" + index,
                                "function",
                                "customerLookup",
                                "{}"
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(message)));
            });
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private static final class SplitToolNameStreamModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("stream only");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(
                    toolCallFrame("customer"),
                    toolCallFrame("Lookup")
            );
        }

        private ChatResponse toolCallFrame(String name) {
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "call-1",
                            "function",
                            name,
                            "{}"
                    )))
                    .build();
            return new ChatResponse(List.of(new Generation(message)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private static final class SafeToolCapableModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private static final class MetadataToolCallStreamModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("stream only");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    "call-1", "function", "Alice", "{}"
            );
            ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                    .keyValue("toolCalls", List.of(toolCall))
                    .build();
            return Flux.just(new ChatResponse(
                    List.of(new Generation(new AssistantMessage(""))),
                    metadata
            ));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
