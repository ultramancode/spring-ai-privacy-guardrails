package io.github.ultramancode.springai.privacy.test;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import io.github.ultramancode.springai.privacy.core.RegexPiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.RegexPiiRule;
import io.github.ultramancode.springai.privacy.springai.PrivacyInputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyLifecycleAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyModelBoundaryAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolContextFactoryTestAccess;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolContextAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallValidationAdvisor;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.ultramancode.springai.privacy.test.PrivacyTestAssertions.assertThatPrivacy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyTestProbeTest {

    private static final Pattern PERSON_TOKEN = OpaquePiiTokenFormat.patternForEntityType("PERSON");

    @Test
    void probeVerifiesModelToolAndSessionBoundariesEndToEnd() {
        PrivacyService privacyService = privacyService();
        PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService);
        PrivacyToolCallbackFactory toolCallbackFactory = new PrivacyToolCallbackFactory(
                privacyService,
                ToolDisclosurePolicy.byToolName(Map.of("customerLookup", Set.of("PERSON")))
        );
        ToolCallback protectedTool = probe.wrapTool(customerLookup(), toolCallbackFactory);
        ChatClient chatClient = ChatClient.builder(probe.wrapModel(new ToolLoopModel()))
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(privacyService),
                        new PrivacyInputAdvisor(privacyService),
                        new PrivacyToolContextAdvisor(privacyService),
                        new PrivacyToolCallValidationAdvisor(privacyService),
                        new PrivacyModelBoundaryAdvisor(privacyService)
                )
                .defaultTools(protectedTool)
                .build();

        String response = chatClient.prompt().user("Find Alice").call().content();

        assertThat(response).isEqualTo("Lookup complete");
        assertThatPrivacy(probe)
                .hasModelRequestCount(2)
                .hasToolCallCount(1)
                .modelRequestsDoNotContainRawValues("Alice", "Bob")
                .modelRequestsContainOpaqueToken("PERSON")
                .modelRequestHasDistinctOpaqueTokenCount(1, "PERSON", 2)
                .toolInputsContain("customerLookup", "Alice")
                .toolOutputsContain("customerLookup", "Bob")
                .hasNoActivePrivacySessions();
        assertThat(probe.modelRequests().get(0).toString())
                .doesNotContain("Alice", "PII_PERSON");
        assertThat(probe.modelRequests()).allSatisfy(snapshot -> assertThat(snapshot.toolDefinitions())
                .singleElement()
                .isEqualTo(new ToolDefinitionSnapshot(
                        "customerLookup",
                        "Looks up a synthetic customer",
                        "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"
                )));
        assertThat(probe.modelRequests().get(1).toolControlFields())
                .containsExactly(
                        new ToolControlFieldSnapshot(
                                ToolControlFieldSnapshot.Source.ASSISTANT_TOOL_CALL,
                                "call-1",
                                "function",
                                "customerLookup"
                        ),
                        new ToolControlFieldSnapshot(
                                ToolControlFieldSnapshot.Source.TOOL_RESPONSE,
                                "call-1",
                                null,
                                "customerLookup"
                        )
                );
        assertThat(probe.modelRequests().get(1).toString())
                .doesNotContain("customerLookup", "call-1");
        assertThatThrownBy(() -> assertThatPrivacy(probe)
                .modelRequestsDoNotContainRawValues("Looks up a synthetic customer"))
                .isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> assertThatPrivacy(probe).modelRequestsDoNotContainRawValues("call-1"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void probeRecordsStreamingInvocations() {
        PrivacyService privacyService = privacyService();
        PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService);
        ChatClient chatClient = ChatClient.builder(probe.wrapModel(new EchoModel()))
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(privacyService),
                        new PrivacyInputAdvisor(privacyService),
                        new PrivacyToolContextAdvisor(privacyService),
                        new PrivacyToolCallValidationAdvisor(privacyService),
                        new PrivacyModelBoundaryAdvisor(privacyService)
                )
                .build();

        String response = chatClient.prompt().user("Find Alice").stream().content().blockFirst();

        assertThat(response).isEqualTo("ok");
        assertThat(probe.modelRequests()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.invocation()).isEqualTo(ModelRequestSnapshot.Invocation.STREAM);
            assertThat(snapshot.modelVisibleContent()).noneMatch(content -> content.contains("Alice"));
        });
        assertThatPrivacy(probe)
                .modelRequestsContainOpaqueToken("PERSON")
                .hasNoActivePrivacySessions();
    }

    @Test
    void probeRecordsSupportedAssistantReasoningContentVisibleToTheModel() {
        PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService());
        AssistantMessage assistant = AssistantMessage.builder()
                .content("safe")
                .properties(Map.of(
                        "reasoningContent", "Alice reasoned",
                        "thinking", "Bob considered",
                        "opaque", new byte[]{1, 2, 3}
                ))
                .build();
        DeepSeekAssistantMessage deepSeekAssistant = new DeepSeekAssistantMessage.Builder()
                .content("also safe")
                .reasoningContent("Carol reasoned")
                .build();

        probe.wrapModel(new EchoModel()).call(new Prompt(List.of(assistant, deepSeekAssistant)));

        assertThat(probe.modelRequests()).singleElement().satisfies(snapshot ->
                assertThat(snapshot.modelVisibleContent())
                        .contains(
                                "safe",
                                "Alice reasoned",
                                "Bob considered",
                                "also safe",
                                "Carol reasoned"
                        )
        );
    }

    @Test
    void probeSnapshotsToolDefinitionAndMetadataAccessorsOnce() {
        PrivacyService service = privacyService();
        PrivacyTestProbe probe = PrivacyTestProbe.create(service);
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        AtomicInteger definitionReads = new AtomicInteger();
        AtomicInteger metadataReads = new AtomicInteger();
        AtomicInteger nameReads = new AtomicInteger();
        AtomicInteger descriptionReads = new AtomicInteger();
        AtomicInteger schemaReads = new AtomicInteger();
        AtomicInteger returnDirectReads = new AtomicInteger();
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                if (definitionReads.incrementAndGet() > 1) {
                    throw new IllegalStateException("definition read more than once");
                }
                return new ToolDefinition() {
                    @Override
                    public String name() {
                        return readOnce(nameReads, "lookup");
                    }

                    @Override
                    public String description() {
                        return readOnce(descriptionReads, "lookup");
                    }

                    @Override
                    public String inputSchema() {
                        return readOnce(schemaReads, "{}");
                    }
                };
            }

            @Override
            public ToolMetadata getToolMetadata() {
                if (metadataReads.incrementAndGet() > 1) {
                    throw new IllegalStateException("metadata read more than once");
                }
                return new ToolMetadata() {
                    @Override
                    public boolean returnDirect() {
                        if (returnDirectReads.incrementAndGet() > 1) {
                            throw new IllegalStateException("returnDirect read more than once");
                        }
                        return false;
                    }
                };
            }

            @Override
            public String call(String input) {
                return "ok";
            }
        };

        ToolCallback wrapped = probe.wrapTool(delegate, factory);

        assertThat(wrapped.getToolDefinition().name()).isEqualTo("lookup");
        assertThat(wrapped.getToolMetadata().returnDirect()).isFalse();
        assertThat(definitionReads).hasValue(1);
        assertThat(metadataReads).hasValue(1);
        assertThat(nameReads).hasValue(1);
        assertThat(descriptionReads).hasValue(1);
        assertThat(schemaReads).hasValue(1);
        assertThat(returnDirectReads).hasValue(1);
    }

    @Test
    void probeVerifiesDefaultDenyToolBoundary() {
        PrivacyService privacyService = privacyService();
        PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService);
        PrivacyToolCallbackFactory toolCallbackFactory = new PrivacyToolCallbackFactory(
                privacyService,
                ToolDisclosurePolicy.denyAll()
        );
        ToolCallback protectedTool = probe.wrapTool(customerLookup(), toolCallbackFactory);
        ChatClient chatClient = ChatClient.builder(probe.wrapModel(new ToolLoopModel()))
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(privacyService),
                        new PrivacyInputAdvisor(privacyService),
                        new PrivacyToolContextAdvisor(privacyService),
                        new PrivacyToolCallValidationAdvisor(privacyService),
                        new PrivacyModelBoundaryAdvisor(privacyService)
                )
                .defaultTools(protectedTool)
                .build();

        chatClient.prompt().user("Find Alice").call().content();

        assertThatPrivacy(probe)
                .toolInputsDoNotContainRawValues("customerLookup", "Alice")
                .toolInputsContainOpaqueToken("customerLookup", "PERSON")
                .hasNoActivePrivacySessions();
    }

    @Test
    void probeRecordsDelegateFailureWhilePrivacyBoundaryPreservesTheException() {
        PrivacyService privacyService = privacyService();
        PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService);
        PrivacyToolCallbackFactory toolCallbackFactory = new PrivacyToolCallbackFactory(
                privacyService,
                ToolDisclosurePolicy.denyAll()
        );
        ToolCallback failingTool = probe.wrapTool(failingTool(), toolCallbackFactory);
        String toolInput = "\"synthetic-secret\"";

        try (PrivacySession session = privacyService.openSession()) {
            assertThatThrownBy(() -> failingTool.call(
                    toolInput,
                    PrivacyToolContextFactoryTestAccess.create(session.handle())
                    ))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("failure containing " + toolInput);
        }
        assertThat(probe.toolCalls()).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.input()).isEqualTo(toolInput);
            assertThat(snapshot.output()).isNull();
            assertThat(snapshot.failureClassName()).isEqualTo(IllegalStateException.class.getName());
            assertThat(snapshot.toString()).doesNotContain("failure containing", "synthetic-secret");
        });

        probe.close();

        assertThat(probe.modelRequests()).isEmpty();
        assertThat(probe.toolCalls()).isEmpty();

        probe.wrapModel(new EchoModel()).call(new Prompt("late Alice"));

        assertThat(probe.modelRequests()).isEmpty();
    }

    @Test
    void probeRejectsAlreadyWrappedToolToKeepRecorderInsideThePrivacyBoundary() {
        PrivacyService privacyService = privacyService();
        PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService);
        PrivacyToolCallbackFactory toolCallbackFactory = new PrivacyToolCallbackFactory(
                privacyService,
                ToolDisclosurePolicy.denyAll()
        );
        ToolCallback alreadyWrapped = toolCallbackFactory.wrap(customerLookup());

        assertThatThrownBy(() -> probe.wrapTool(alreadyWrapped, toolCallbackFactory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("delegate must be an unwrapped tool callback");
    }

    @Test
    void probeRejectsAlreadyWrappedModelToAvoidDuplicateSnapshots() {
        PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService());
        ChatModel wrapped = probe.wrapModel(new EchoModel());

        assertThatThrownBy(() -> probe.wrapModel(wrapped))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("delegate must be an unwrapped chat model");
    }

    @Test
    void modelContentAssertionsFailWhenTheProbeRecordedNoModelRequest() {
        PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService());

        assertThatThrownBy(() -> assertThatPrivacy(probe).modelRequestsDoNotContainRawValues("Alice"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("at least one recorded model request");
    }

    private PrivacyService privacyService() {
        return new PrivacyService(
                List.of(new RegexPiiAnalyzer(List.of(
                        new RegexPiiRule("PERSON", "\\b(?:Alice|Bob)\\b", 0.99, 0)
                ))),
                PiiAnalysisOptions.defaults()
        );
    }

    private static String readOnce(AtomicInteger reads, String value) {
        if (reads.incrementAndGet() > 1) {
            throw new IllegalStateException("accessor read more than once");
        }
        return value;
    }

    private ToolCallback customerLookup() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("customerLookup")
                        .description("Looks up a synthetic customer")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public String call(String input) {
                return "Customer Bob is active";
            }
        };
    }

    private ToolCallback failingTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("failingTool")
                        .description("Fails for probe testing")
                        .inputSchema("{\"type\":\"string\"}")
                        .build();
            }

            @Override
            public String call(String input) {
                throw new IllegalStateException("failure containing " + input);
            }
        };
    }

    private static final class ToolLoopModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse call(Prompt prompt) {
            if (this.calls.incrementAndGet() == 1) {
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
            return response("Lookup complete");
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

    private static final class EchoModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            return response("ok");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(response("ok"));
        }
    }

    private static ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
