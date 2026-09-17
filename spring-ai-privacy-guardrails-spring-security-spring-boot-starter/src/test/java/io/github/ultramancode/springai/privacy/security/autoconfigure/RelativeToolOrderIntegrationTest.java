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
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
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
        ApplicationContextRunner runner = privacyEnabled ? privacyContextRunner() : contextRunner();
        runner.run(context -> {
            ToolCallingAdvisor.Builder<?> toolAdvisorBuilder = ToolCallingAdvisor.builder().advisorOrder(0);
            ToolCallback callback = tool("customerLookup", calls);
            ChatClient.Builder builder;
            if (privacyEnabled) {
                builder = protectedBuilder(context, toolAdvisorBuilder);
                callback = context.getBean(PrivacyToolCallbackFactory.class).wrap(callback);
            } else {
                builder = context.getBean(ToolAuthorizationChatClientFactory.class)
                        .builder(context.getBean(OpenAiChatModel.class), toolAdvisorBuilder);
            }
            ToolCallingAdvisor clientToolAdvisor = ToolCallingAdvisor.builder().advisorOrder(0).build();
            ChatClient client = builder.defaultTools(callback)
                    .defaultAdvisors(clientToolAdvisor).build();
            authenticate();
            ToolSearchToolCallingAdvisor requestToolSearchAdvisor = ToolSearchToolCallingAdvisor.builder()
                    .toolIndex(index)
                    .advisorOrder(0)
                    .systemMessageSuffix("Search for tools before using them.")
                    .build();
            ChatClient.ChatClientRequestSpec request = client.prompt().user("Run lookup")
                    .advisors(requestToolSearchAdvisor);

            assertThatThrownBy(() -> executeRequest(request, streaming))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("At most one ToolAdvisor is allowed", "found 2");
            verifyNoInteractions(index);
            assertThat(calls).hasValue(0);
            assertThat(this.modelRequests).isEmpty();
        });
    }

    @ParameterizedTest
    @CsvSource({
            "false, false, 0", "true, false, 0", "false, true, 0", "true, true, 0",
            "false, false, 100", "true, false, 100", "false, true, 100", "true, true, 100",
            // Without output protection, PrivacyInputAdvisor.DEFAULT_ORDER + 2 leaves room for
            // PrivacyToolContextAdvisor.
            "false, false, -2147483396", "true, false, -2147483396",
            // With output protection, PrivacyInputAdvisor.DEFAULT_ORDER + 3 leaves room for
            // PrivacyOutputAdvisor and PrivacyToolContextAdvisor.
            "false, true, -2147483395", "true, true, -2147483395",
            // Maximum: PrivacyModelBoundaryAdvisor.DEFAULT_ORDER - 2 leaves room for
            // PrivacyToolCallValidationAdvisor.
            "false, false, 2147483642", "true, false, 2147483642",
            "false, true, 2147483642", "true, true, 2147483642"
    })
    void toolSearchProtectsPiiAtSupportedOrders(boolean streaming, boolean outputEnabled, int toolOrder) throws IOException {
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

        privacyContextRunner().withPropertyValues("spring.ai.privacy.output.enabled=" + outputEnabled).run(context -> {
            PrivacyToolCallbackFactory factory = context.getBean(PrivacyToolCallbackFactory.class);
            ChatClient client = protectedBuilder(context,
                            ToolSearchToolCallingAdvisor.builder().toolIndex(index).advisorOrder(toolOrder)
                                    .systemMessageSuffix("Search for tools before using them."))
                    .defaultTools(factory.wrap(tool("customerLookup", customerInput::set)),
                            factory.wrap(tool("adminDelete", new AtomicInteger()))).build();
            authenticate();
            ChatClient.ChatClientRequestSpec request = client.prompt().user("Find EMP-0042")
                    .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "combined-session"));

            String response = executeRequest(request, streaming);
            assertThat(response).isEqualTo("done");
            assertThat(customerInput).hasValue("{\"id\":\"EMP-0042\"}");
            verify(index).indexTools(eq("combined-session"), argThat(references ->
                    references.stream().map(ToolReference::toolName).toList().equals(List.of("customerLookup"))));
            ArgumentCaptor<ToolSearchRequest> searchRequestCaptor = ArgumentCaptor.forClass(ToolSearchRequest.class);
            verify(index).search(searchRequestCaptor.capture());
            String searchQuery = searchRequestCaptor.getValue().query();
            assertThat(searchQuery).contains("Find ").doesNotContain("EMP-0042");
            assertThat(OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID")
                    .matcher(searchQuery).find()).isTrue();
            // Three model calls: request toolSearchTool, request customerLookup, then return the final answer.
            assertThat(this.modelRequests).hasSize(3).allSatisfy(modelRequest ->
                    assertThat(modelRequest).doesNotContain("EMP-0042", "adminDelete"));
        });
    }

    @ParameterizedTest
    // With tool order 0, -20 places memory outside the tool loop and 5 places it inside.
    @CsvSource({"false, -20", "true, -20", "false, 5", "true, 5"})
    void memoryOnEitherSideOfTheToolLoopDoesNotDuplicateHistory(boolean streaming, int memoryOrder) throws IOException {
        if (streaming) {
            startModelServer(toolStreamResponse("customerLookup"), finalStreamResponse());
        } else {
            startModelServer(toolResponse("customerLookup"), finalResponse());
        }
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(20).build();
        memory.add("memory-session", List.of(new UserMessage("Previous question"),
                new AssistantMessage("Previous answer")));
        privacyContextRunner().run(context -> {
            PrivacyToolCallbackFactory factory = context.getBean(PrivacyToolCallbackFactory.class);
            ChatClient client = protectedBuilder(context, ToolCallingAdvisor.builder().advisorOrder(0))
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).order(memoryOrder).build())
                    .defaultTools(factory.wrap(tool("customerLookup", new AtomicInteger())))
                    .build();
            authenticate();

            ChatClient.ChatClientRequestSpec request = client.prompt().user("Run current lookup")
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "memory-session"));
            String response = executeRequest(request, streaming);
            assertThat(response).isEqualTo("done");

            // Check for duplicate history in the second model request, after tool execution.
            JsonNode messages = JsonMapper.builder().build().readTree(this.modelRequests.get(1)).path("messages");
            int currentQuestionCount = 0;
            int toolCallCount = 0;
            for (JsonNode message : messages) {
                if ("Run current lookup".equals(message.path("content").asString())) {
                    currentQuestionCount++;
                }
                toolCallCount += message.path("tool_calls").size();
            }
            assertThat(currentQuestionCount).isEqualTo(1);
            assertThat(toolCallCount).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void responseLimitsApplyBeforeToolExecution(boolean streaming, boolean outputEnabled) throws IOException {
        String arguments = "{\\\"payload\\\":\\\"" + "x".repeat(200) + "\\\"}";
        startModelServer(
                streaming ? toolStreamResponse("customerLookup", arguments) : toolResponse("customerLookup", arguments),
                streaming ? finalStreamResponse() : finalResponse());
        AtomicInteger calls = new AtomicInteger();
        privacyContextRunner().withPropertyValues("spring.ai.privacy.response-inspection.max-characters=64",
                        "spring.ai.privacy.output.enabled=" + outputEnabled)
                .run(context -> {
                    PrivacyToolCallbackFactory factory = context.getBean(PrivacyToolCallbackFactory.class);
                    ChatClient client = protectedBuilder(context, ToolCallingAdvisor.builder().advisorOrder(0))
                            .defaultTools(factory.wrap(tool("customerLookup", calls))).build();
                    authenticate();

                    ChatClient.ChatClientRequestSpec request = client.prompt().user("Run lookup");
                    assertThatThrownBy(() -> executeRequest(request, streaming))
                            .hasMessageContaining("inspection limit");
                    assertThat(calls).hasValue(0);
                    assertThat(this.modelRequests).hasSize(1);
                });
    }

    @Test
    void differentClientOrdersRemainIndependent() throws IOException {
        startModelServer(toolResponse("customerLookup"), finalResponse(),
                toolResponse("customerLookup"), finalResponse());
        privacyContextRunner().run(context -> {
            AtomicInteger calls = new AtomicInteger();
            List<Integer> firstToolOrders = new ArrayList<>();
            List<Integer> secondToolOrders = new ArrayList<>();
            ToolCallback callback = context.getBean(PrivacyToolCallbackFactory.class).wrap(tool("customerLookup", calls));
            ToolCallingAdvisor.Builder<?> toolAdvisorBuilder = ToolCallingAdvisor.builder().advisorOrder(0);
            ChatClient first = protectedBuilder(context, toolAdvisorBuilder)
                    .defaultAdvisors(new ToolOrderRecordingAdvisor(firstToolOrders))
                    .defaultTools(callback).build();
            toolAdvisorBuilder.advisorOrder(100);
            ChatClient second = protectedBuilder(context, toolAdvisorBuilder)
                    .defaultAdvisors(new ToolOrderRecordingAdvisor(secondToolOrders))
                    .defaultTools(callback).build();
            authenticate();
            assertThat(first.prompt().user("First lookup").call().content()).isEqualTo("done");
            assertThat(second.prompt().user("Second lookup").call().content()).isEqualTo("done");
            assertThat(firstToolOrders).containsExactly(0);
            assertThat(secondToolOrders).containsExactly(100);
            assertThat(calls).hasValue(2);
            assertThat(this.modelRequests).hasSize(4);
        });
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void priorityToolAdvisorMustBeRejectedBeforeLoopEntry(boolean streaming, boolean privacyEnabled) throws IOException {
        startModelServer(finalResponse());
        ApplicationContextRunner runner = privacyEnabled ? privacyContextRunner() : contextRunner();
        runner.run(context -> {
            AtomicInteger toolLoopStarts = new AtomicInteger();
            AtomicInteger executions = new AtomicInteger();
            PriorityToolAdvisorBuilder toolAdvisorBuilder = new PriorityToolAdvisorBuilder(toolLoopStarts)
                    .advisorOrder(ToolCallingAdvisor.DEFAULT_ORDER);
            authenticate();
            Throwable rejection = catchThrowable(() -> {
                ToolCallback callback = tool("customerLookup", executions);
                ChatClient.Builder builder;
                if (privacyEnabled) {
                    builder = protectedBuilder(context, toolAdvisorBuilder);
                    callback = context.getBean(PrivacyToolCallbackFactory.class).wrap(callback);
                } else {
                    builder = context.getBean(ToolAuthorizationChatClientFactory.class)
                            .builder(context.getBean(OpenAiChatModel.class), toolAdvisorBuilder);
                }
                ChatClient client = builder.defaultTools(callback).build();
                ChatClient.ChatClientRequestSpec request = client.prompt().user("Lookup");
                executeRequest(request, streaming);
            });
            assertThat(rejection).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PriorityOrdered", "authorization lifecycle");
            assertThat(toolLoopStarts).hasValue(0);
            assertThat(executions).hasValue(0);
            assertThat(this.modelRequests).isEmpty();
        });
    }

    // T is the ToolAdvisor order supplied to the factory.
    // PrivacyChatClientConfigurer keeps the library's default orders for these advisors:
    // PrivacyInputAdvisor.DEFAULT_ORDER = -2_147_483_398
    // PrivacyModelBoundaryAdvisor.DEFAULT_ORDER = Integer.MAX_VALUE - 3
    @ParameterizedTest
    @ValueSource(ints = {
            // PrivacyToolContextAdvisor (T - 1) would have an order below Integer.MIN_VALUE.
            Integer.MIN_VALUE,
            // PrivacyToolContextAdvisor (T - 1) must have a greater order value than
            // PrivacyInputAdvisor (DEFAULT_ORDER). This value would make their orders equal.
            -2_147_483_397,
            // PrivacyToolCallValidationAdvisor (T + 1) must have a smaller order value than
            // PrivacyModelBoundaryAdvisor (DEFAULT_ORDER). This value would make their orders equal.
            Integer.MAX_VALUE - 4,
            // ToolAdvisor (T) must have a smaller order value than PrivacyModelBoundaryAdvisor (DEFAULT_ORDER).
            // This value would make their orders equal.
            Integer.MAX_VALUE - 3,
            // The order of PrivacyToolCallValidationAdvisor (T + 1) would exceed Integer.MAX_VALUE.
            Integer.MAX_VALUE
    })
    void rejectsUnplaceableOrdersBeforeApplyingClientCustomizers(int order) throws IOException {
        startModelServer(finalResponse());
        assertToolOrderRejectedBeforeCustomization(order, false);
        assertToolOrderRejectedBeforeCustomization(order, true);
    }

    @Test
    void rejectsToolOrderWithoutRoomForEnabledOutputProtection() throws IOException {
        startModelServer(finalResponse());
        // With output protection enabled, PrivacyOutputAdvisor (T - 2) must have a greater order value than
        // PrivacyInputAdvisor.DEFAULT_ORDER (-2_147_483_398). This value would make the two orders equal.
        assertToolOrderRejectedBeforeCustomization(-2_147_483_396, true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDuplicatePrivacyToolCallValidationAdvisor(boolean streaming) throws IOException {
        startModelServer(finalResponse());
        ToolIndex index = mock(ToolIndex.class);
        privacyContextRunner().run(context -> {
            ChatClient client = protectedBuilder(context, ToolSearchToolCallingAdvisor.builder()
                    .toolIndex(index).systemMessageSuffix("Find tools.").advisorOrder(0))
                    .defaultTools(context.getBean(PrivacyToolCallbackFactory.class)
                            .wrap(tool("customerLookup", new AtomicInteger()))).build();
            authenticate();
            ChatClient.ChatClientRequestSpec request = client.prompt().user("Lookup").advisors(
                    new PrivacyToolCallValidationAdvisor(context.getBean(PrivacyService.class), 1));
            assertThatThrownBy(() -> executeRequest(request, streaming))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessageContaining("exactly one managed PrivacyToolCallValidationAdvisor");
            verifyNoInteractions(index);
            assertThat(this.modelRequests).isEmpty();
        });
    }

    @Test
    void noToolRequestsCanDisableAutomaticRegistration() throws IOException {
        startModelServer(finalResponse());
        privacyContextRunner().run(context -> {
            ChatClient client = protectedBuilder(context, ToolCallingAdvisor.builder().advisorOrder(0)).build();
            ChatClient.ChatClientRequestSpec request = client.prompt().user("Hello")
                    .advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false));
            String response = request.call().content();

            assertThat(response).isEqualTo("done");
        });
    }

    @Test
    void usesTheConfiguredToolAdvisorOrder() throws IOException {
        startModelServer(toolResponse("customerLookup"), finalResponse());
        privacyContextRunner().withPropertyValues("spring.ai.chat.client.tool-calling.advisor-order=100")
                .run(context -> {
                    AtomicInteger calls = new AtomicInteger();
                    List<Integer> toolOrders = new ArrayList<>();
                    ChatClient client = context.getBean(PrivacySecurityChatClientFactory.class)
                            .builder(context.getBean(OpenAiChatModel.class))
                            .defaultAdvisors(new ToolOrderRecordingAdvisor(toolOrders))
                            .defaultTools(context.getBean(PrivacyToolCallbackFactory.class)
                                    .wrap(tool("customerLookup", calls))).build();
                    authenticate();
                    assertThat(client.prompt().user("Lookup").call().content()).isEqualTo("done");
                    assertThat(toolOrders).containsExactly(100);
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
            ChatClient client = protectedBuilder(context, ToolCallingAdvisor.builder().advisorOrder(0))
                    .defaultTools(context.getBean(PrivacyToolCallbackFactory.class)
                            .wrap(tool("customerLookup", executions))).build();
            authenticate();
            // Match PrivacyToolCallValidationAdvisor's order: tool order 0 + 1.
            ChatClient.ChatClientRequestSpec request = client.prompt().user("Lookup").advisors(new CountingAdvisor(observations, 1));
            String result = executeRequest(request, streaming);
            assertThat(result).isEqualTo("done");
            assertThat(executions).hasValue(1);
            assertThat(observations).hasValue(2);
        });
    }

    private void assertToolOrderRejectedBeforeCustomization(int toolOrder, boolean outputEnabled) {
        AtomicInteger customizations = new AtomicInteger();
        ChatClientBuilderCustomizer customizer = builder -> customizations.incrementAndGet();
        privacyContextRunner().withPropertyValues("spring.ai.privacy.output.enabled=" + outputEnabled)
                .withBean(ChatClientBuilderCustomizer.class, () -> customizer).run(context -> {
            assertThatThrownBy(() -> protectedBuilder(context, ToolCallingAdvisor.builder().advisorOrder(toolOrder)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("order");
            assertThat(customizations).hasValue(0);
            assertThat(this.modelRequests).isEmpty();
        });
    }

    private ChatClient.Builder protectedBuilder(
            AssertableApplicationContext context, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        return context.getBean(PrivacySecurityChatClientFactory.class)
                .builder(context.getBean(OpenAiChatModel.class), toolAdvisorBuilder);
    }

    private static final class PriorityToolAdvisorBuilder extends ToolCallingAdvisor.Builder<PriorityToolAdvisorBuilder> {
        private final AtomicInteger toolLoopStarts;

        PriorityToolAdvisorBuilder(AtomicInteger toolLoopStarts) {
            this.toolLoopStarts = toolLoopStarts;
        }

        @Override
        protected ToolCallingAdvisor.Builder<?> newCopy() {
            return new PriorityToolAdvisorBuilder(this.toolLoopStarts);
        }

        @Override
        public ToolCallingAdvisor build() {
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

        @Override
        public ChatClientResponse adviseCall(
                ChatClientRequest request,
                CallAdvisorChain chain) {
            this.toolLoopStarts.incrementAndGet();
            return super.adviseCall(request, chain);
        }

        @Override
        public Flux<ChatClientResponse> adviseStream(
                ChatClientRequest request,
                StreamAdvisorChain chain) {
            this.toolLoopStarts.incrementAndGet();
            return super.adviseStream(request, chain);
        }
    }

    private record ToolOrderRecordingAdvisor(List<Integer> toolOrders) implements CallAdvisor {
        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            this.toolOrders.addAll(chain.getCallAdvisors().stream()
                    .filter(ToolAdvisor.class::isInstance)
                    .map(CallAdvisor::getOrder)
                    .toList());
            return chain.nextCall(request);
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }

        @Override
        public String getName() {
            return "ToolOrderRecordingAdvisor";
        }
    }

    private record CountingAdvisor(AtomicInteger calls, int order) implements CallAdvisor, StreamAdvisor {
        @Override
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            this.calls.incrementAndGet();
            return chain.nextCall(request);
        }

        @Override
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
            this.calls.incrementAndGet();
            return chain.nextStream(request);
        }

        @Override
        public int getOrder() {
            return this.order;
        }

        @Override
        public String getName() {
            return "CountingAdvisor";
        }
    }
}
