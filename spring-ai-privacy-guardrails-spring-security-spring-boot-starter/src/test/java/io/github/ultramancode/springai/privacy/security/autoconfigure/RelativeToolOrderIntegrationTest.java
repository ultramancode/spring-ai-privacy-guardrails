package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallValidationAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.core.PriorityOrdered;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RelativeToolOrderIntegrationTest extends ToolAuthorizationIntegrationTestSupport {

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void springAiRejectsMultipleToolAdvisorsBeforeExecution(boolean streaming, boolean privacyEnabled)
            throws IOException {
        startModelServer(finalResponse());
        ToolIndex index = mock(ToolIndex.class);
        AtomicInteger calls = new AtomicInteger();
        var runner = privacyEnabled ? privacyContextRunner() : contextRunner();
        runner.run(context -> {
            var template = ToolCallingAdvisor.builder().advisorOrder(0);
            var callback = tool("customerLookup", calls);
            ChatClient.Builder builder;
            if (privacyEnabled) {
                builder = protectedBuilder(context, template);
                callback = context.getBean(PrivacyToolCallbackFactory.class).wrap(callback);
            } else {
                builder = context.getBean(ToolAuthorizationChatClientFactory.class)
                        .builder(context.getBean(OpenAiChatModel.class), template);
            }
            ChatClient client = builder.defaultTools(callback)
                    .defaultAdvisors(ToolCallingAdvisor.builder().advisorOrder(0).build()).build();
            authenticate();
            var request = client.prompt().user("Run lookup")
                    .advisors(ToolSearchToolCallingAdvisor.builder().toolIndex(index).advisorOrder(0)
                            .systemMessageSuffix("Search for tools before using them.").build());

            assertThatThrownBy(() -> {
                if (streaming) {
                    request.stream().content().collectList().block(Duration.ofSeconds(10));
                } else {
                    request.call().content();
                }
            }).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("At most one ToolAdvisor is allowed", "found 2");
            verifyNoInteractions(index);
            assertThat(calls).hasValue(0);
            assertThat(this.modelRequests).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource({
            "false, 0", "true, 0", "false, 100", "true, 100",
            "false, -2147483395", "true, -2147483395", "false, 2147483644", "true, 2147483644"
    })
    void toolSearchProtectsPiiAtSupportedOrders(boolean streaming, int toolOrder) throws IOException {
        verifyCombinedToolSearch(streaming, toolOrder);
    }

    @ParameterizedTest
    @CsvSource({"false, -20", "true, -20", "false, 5", "true, 5"})
    void memoryOnEitherSideOfTheToolLoopDoesNotDuplicateHistory(boolean streaming, int memoryOrder) throws IOException {
        verifyMemoryHistory(streaming, memoryOrder);
    }

    private void verifyCombinedToolSearch(boolean streaming, int toolOrder) throws IOException {
        String searchArguments = "{\\\"SEARCH_QUERY_PARAMETER\\\":\\\"Find EMP-0042\\\"}";
        String lookupArguments = "{\\\"id\\\":\\\"EMP-0042\\\"}";
        if (streaming) {
            startModelServer(toolStreamResponse("toolSearchTool", searchArguments),
                    toolStreamResponse("customerLookup", lookupArguments), finalStreamResponse());
        } else {
            startModelServer(toolResponse("toolSearchTool", searchArguments),
                    toolResponse("customerLookup", lookupArguments), finalResponse());
        }
        ToolIndex index = mock(ToolIndex.class);
        when(index.search(any())).thenReturn(ToolSearchResponse.builder()
                .addToolReference(ToolReference.builder().toolName("customerLookup")
                        .summary("Find customer").build()).build());
        AtomicReference<String> customerInput = new AtomicReference<>();

        privacyContextRunner().run(context -> {
            PrivacyToolCallbackFactory factory = context.getBean(PrivacyToolCallbackFactory.class);
            ChatClient client = protectedBuilder(context,
                            ToolSearchToolCallingAdvisor.builder().toolIndex(index).advisorOrder(toolOrder)
                                    .systemMessageSuffix("Search for tools before using them."))
                    .defaultTools(factory.wrap(tool("customerLookup", customerInput::set)),
                            factory.wrap(tool("adminDelete", new AtomicInteger()))).build();
            authenticate();
            ChatClient.ChatClientRequestSpec request = client.prompt().user("Find EMP-0042")
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "combined-session"));

            String response = streaming
                    ? request.stream().content().collectList().map(parts -> String.join("", parts))
                            .block(Duration.ofSeconds(10))
                    : request.call().content();
            assertThat(response).isEqualTo("done");
            assertThat(customerInput).hasValue("{\"id\":\"EMP-0042\"}");
            verify(index).indexTools(eq("combined-session"), argThat(references ->
                    references.stream().map(ToolReference::toolName).toList().equals(List.of("customerLookup"))));
            ArgumentCaptor<ToolSearchRequest> searchRequest = ArgumentCaptor.forClass(ToolSearchRequest.class);
            verify(index).search(searchRequest.capture());
            assertThat(searchRequest.getValue().query()).contains("Find ").doesNotContain("EMP-0042");
            assertThat(OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID")
                    .matcher(searchRequest.getValue().query()).find()).isTrue();
            assertThat(this.modelRequests).hasSize(3).allSatisfy(modelRequest ->
                    assertThat(modelRequest).doesNotContain("EMP-0042", "adminDelete"));
        });
    }

    private void verifyMemoryHistory(boolean streaming, int memoryOrder) throws IOException {
        if (streaming) {
            startModelServer(toolStreamResponse("customerLookup"), finalStreamResponse());
        } else {
            startModelServer(toolResponse("customerLookup"), finalResponse());
        }
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(20).build();
        memory.add("memory-session", List.of(new UserMessage("Previous question"),
                new AssistantMessage("Previous answer")));
        privacyContextRunner().run(context -> {
            ChatClient client = protectedBuilder(context, ToolCallingAdvisor.builder().advisorOrder(0))
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).order(memoryOrder).build())
                    .defaultTools(context.getBean(PrivacyToolCallbackFactory.class).wrap(tool("customerLookup", new AtomicInteger()))).build();
            authenticate();

            var request = client.prompt().user("Run current lookup")
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "memory-session"));
            String response = streaming ? request.stream().content().collectList()
                    .map(parts -> String.join("", parts)).block(Duration.ofSeconds(10)) : request.call().content();
            assertThat(response).isEqualTo("done");

            JsonNode messages = JsonMapper.builder().build().readTree(this.modelRequests.get(1)).path("messages");
            int currentUsers = 0;
            int toolCalls = 0;
            for (JsonNode message : messages) {
                if ("Run current lookup".equals(message.path("content").asString())) {
                    currentUsers++;
                }
                toolCalls += message.path("tool_calls").size();
            }
            assertThat(currentUsers).isEqualTo(1);
            assertThat(toolCalls).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void responseLimitsApplyBeforeToolExecution(boolean streaming, boolean outputEnabled) throws IOException {
        String arguments = "{\\\"payload\\\":\\\"" + "x".repeat(200) + "\\\"}";
        startModelServer(streaming ? toolStreamResponse("customerLookup", arguments) : toolResponse("customerLookup", arguments),
                streaming ? finalStreamResponse() : finalResponse());
        AtomicInteger calls = new AtomicInteger();
        privacyContextRunner().withPropertyValues("spring.ai.privacy.response-inspection.max-characters=64",
                        "spring.ai.privacy.output.enabled=" + outputEnabled)
                .run(context -> {
                    PrivacyToolCallbackFactory factory = context.getBean(PrivacyToolCallbackFactory.class);
                    ChatClient client = protectedBuilder(context, ToolCallingAdvisor.builder().advisorOrder(0))
                            .defaultTools(factory.wrap(tool("customerLookup", calls))).build();
                    authenticate();

                    assertThatThrownBy(() -> {
                        var request = client.prompt().user("Run lookup");
                        if (streaming) request.stream().content().collectList().block(Duration.ofSeconds(10));
                        else request.call().content();
                    })
                            .hasMessageContaining("inspection limit");
                    assertThat(calls).hasValue(0);
                    assertThat(this.modelRequests).hasSize(1);
                });
    }

    private ChatClient.Builder protectedBuilder(AssertableApplicationContext context, ToolCallingAdvisor.Builder<?> template) {
        return context.getBean(PrivacySecurityChatClientFactory.class)
                .builder(context.getBean(OpenAiChatModel.class), template);
    }

    @Test
    void differentClientOrdersRemainIndependent() throws IOException {
        startModelServer(toolResponse("customerLookup"), finalResponse(),
                toolResponse("customerLookup"), finalResponse());
        privacyContextRunner().run(context -> {
            AtomicInteger calls = new AtomicInteger();
            var callback = context.getBean(PrivacyToolCallbackFactory.class).wrap(tool("customerLookup", calls));
            var template = ToolCallingAdvisor.builder().advisorOrder(0);
            var first = protectedBuilder(context, template).defaultTools(callback).build();
            template.advisorOrder(100);
            var second = protectedBuilder(context, template).defaultTools(callback).build();
            authenticate();
            assertThat(first.prompt().user("First lookup").call().content()).isEqualTo("done");
            assertThat(second.prompt().user("Second lookup").call().content()).isEqualTo("done");
            assertThat(calls).hasValue(2);
            assertThat(this.modelRequests).hasSize(4);
        });
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void priorityTemplateMustBeRejectedBeforeLoopEntry(boolean streaming, boolean privacyEnabled) throws IOException {
        startModelServer(finalResponse());
        var runner = privacyEnabled ? privacyContextRunner() : contextRunner();
        runner.run(context -> {
            AtomicInteger toolLoopStarts = new AtomicInteger();
            AtomicInteger executions = new AtomicInteger();
            var template = new PriorityToolAdvisorBuilder(toolLoopStarts).advisorOrder(ToolCallingAdvisor.DEFAULT_ORDER);
            authenticate();
            Throwable rejection = catchThrowable(() -> {
                var callback = tool("customerLookup", executions);
                ChatClient.Builder builder;
                if (privacyEnabled) {
                    builder = protectedBuilder(context, template);
                    callback = context.getBean(PrivacyToolCallbackFactory.class).wrap(callback);
                } else {
                    builder = context.getBean(ToolAuthorizationChatClientFactory.class)
                            .builder(context.getBean(OpenAiChatModel.class), template);
                }
                var client = builder.defaultTools(callback).build();
                var request = client.prompt().user("Lookup");
                if (streaming) {
                    request.stream().content().collectList().block(Duration.ofSeconds(10));
                } else {
                    request.call().content();
                }
            });
            assertThat(rejection).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PriorityOrdered", "authorization lifecycle");
            assertThat(toolLoopStarts).as("Reject unsupported priority semantics before entering the tool loop").hasValue(0);
            assertThat(executions).hasValue(0);
            assertThat(this.modelRequests).isEmpty();
        });
    }

    private static final class PriorityToolAdvisorBuilder extends ToolCallingAdvisor.Builder<PriorityToolAdvisorBuilder> {
        private final AtomicInteger toolLoopStarts;
        PriorityToolAdvisorBuilder(AtomicInteger toolLoopStarts) { this.toolLoopStarts = toolLoopStarts; }
        @Override protected ToolCallingAdvisor.Builder<?> newCopy() {
            return new PriorityToolAdvisorBuilder(this.toolLoopStarts);
        }
        @Override public ToolCallingAdvisor build() {
            return new PriorityToolAdvisor(getToolCallingManager(), getToolExecutionEligibilityChecker(),
                    getAdvisorOrder(), isConversationHistoryEnabled(), this.toolLoopStarts);
        }
    }

    private static final class PriorityToolAdvisor extends ToolCallingAdvisor
            implements PriorityOrdered {
        private final AtomicInteger toolLoopStarts;
        PriorityToolAdvisor(ToolCallingManager manager,
                ToolExecutionEligibilityChecker checker,
                int order, boolean history, AtomicInteger toolLoopStarts) {
            super(manager, checker, order, history);
            this.toolLoopStarts = toolLoopStarts;
        }
        @Override public ChatClientResponse adviseCall(
                ChatClientRequest request,
                CallAdvisorChain chain) {
            this.toolLoopStarts.incrementAndGet();
            return super.adviseCall(request, chain);
        }
        @Override public Flux<ChatClientResponse> adviseStream(
                ChatClientRequest request,
                StreamAdvisorChain chain) {
            this.toolLoopStarts.incrementAndGet();
            return super.adviseStream(request, chain);
        }
    }


    @ParameterizedTest
    @ValueSource(ints = {-2147483648, -2147483396, 2147483645, 2147483646, 2147483647})
    void rejectsUnplaceableOrdersBeforeApplyingClientCustomizers(int order) throws IOException {
        startModelServer(finalResponse());
        AtomicInteger customizations = new AtomicInteger();
        privacyContextRunner().withBean(ChatClientBuilderCustomizer.class,
                () -> builder -> customizations.incrementAndGet()).run(context -> {
            assertThatThrownBy(() -> protectedBuilder(context, ToolCallingAdvisor.builder().advisorOrder(order)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("order");
            assertThat(customizations).hasValue(0);
            assertThat(this.modelRequests).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDuplicateRequestBoundariesBeforeToolSearchInitialization(boolean streaming) throws IOException {
        startModelServer(finalResponse());
        ToolIndex index = mock(ToolIndex.class);
        privacyContextRunner().run(context -> {
            var client = protectedBuilder(context, ToolSearchToolCallingAdvisor.builder()
                    .toolIndex(index).systemMessageSuffix("Find tools.").advisorOrder(0))
                    .defaultTools(context.getBean(PrivacyToolCallbackFactory.class)
                            .wrap(tool("customerLookup", new AtomicInteger()))).build();
            authenticate();
            var request = client.prompt().user("Lookup").advisors(
                    new PrivacyToolCallValidationAdvisor(context.getBean(PrivacyService.class), 1));
            assertThatThrownBy(() -> {
                if (streaming) request.stream().content().collectList().block(Duration.ofSeconds(10));
                else request.call().content();
            }).isInstanceOf(PrivacyGuardrailException.class);
            verifyNoInteractions(index);
            assertThat(this.modelRequests).isEmpty();
        });
    }

    @Test
    void noToolRequestsCanDisableAutomaticRegistration() throws IOException {
        startModelServer(finalResponse());
        privacyContextRunner().run(context -> {
            var client = protectedBuilder(context, ToolCallingAdvisor.builder().advisorOrder(0)).build();
            assertThat(client.prompt().user("Hello").advisors(
                    AdvisorParams.toolCallingAdvisorAutoRegister(false))
                    .call().content()).isEqualTo("done");
        });
    }

    @Test
    void usesTheConfiguredToolTemplateOrder() throws IOException {
        startModelServer(toolResponse("customerLookup"), finalResponse());
        privacyContextRunner().withPropertyValues("spring.ai.chat.client.tool-calling.advisor-order=100")
                .run(context -> {
                    AtomicInteger calls = new AtomicInteger();
                    var client = context.getBean(PrivacySecurityChatClientFactory.class)
                            .builder(context.getBean(OpenAiChatModel.class))
                            .defaultTools(context.getBean(PrivacyToolCallbackFactory.class)
                                    .wrap(tool("customerLookup", calls))).build();
                    authenticate();
                    assertThat(client.prompt().user("Lookup").call().content()).isEqualTo("done");
                    assertThat(calls).hasValue(1);
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allowsUnrelatedRequestAdvisorsAtTheValidationOrder(boolean streaming) throws IOException {
        startModelServer(streaming ? toolStreamResponse("customerLookup") : toolResponse("customerLookup"),
                streaming ? finalStreamResponse() : finalResponse());
        privacyContextRunner().run(context -> {
            AtomicInteger executions = new AtomicInteger();
            AtomicInteger observations = new AtomicInteger();
            var client = protectedBuilder(context, ToolCallingAdvisor.builder().advisorOrder(0))
                    .defaultTools(context.getBean(PrivacyToolCallbackFactory.class)
                            .wrap(tool("customerLookup", executions))).build();
            authenticate();
            var request = client.prompt().user("Lookup").advisors(new CountingAdvisor(observations));
            String result = streaming ? request.stream().content().collectList()
                    .map(parts -> String.join("", parts)).block(Duration.ofSeconds(10)) : request.call().content();
            assertThat(result).isEqualTo("done");
            assertThat(executions).hasValue(1);
            assertThat(observations).hasValue(2);
        });
    }

    private record CountingAdvisor(AtomicInteger calls) implements CallAdvisor, StreamAdvisor {
        @Override public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            this.calls.incrementAndGet();
            return chain.nextCall(request);
        }
        @Override public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
            this.calls.incrementAndGet();
            return chain.nextStream(request);
        }
        @Override public int getOrder() { return 1; }
        @Override public String getName() { return "CountingAdvisor"; }
    }
}
