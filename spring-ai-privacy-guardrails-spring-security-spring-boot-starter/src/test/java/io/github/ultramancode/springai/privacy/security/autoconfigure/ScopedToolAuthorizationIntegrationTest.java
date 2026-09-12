package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Timeout(20)
class ScopedToolAuthorizationIntegrationTest extends ToolAuthorizationIntegrationTestSupport {

    @Test
    void configuredAndOrdinaryClientsShareAModelWithoutSharingAuthorization() throws IOException {
        startModelServer(toolResponse("customerLookup"), finalResponse(),
                toolResponse("adminDelete"), finalResponse());
        AtomicInteger customerCalls = new AtomicInteger();
        AtomicInteger adminCalls = new AtomicInteger();
        ToolCallback customer = tool("customerLookup", customerCalls);
        ToolCallback admin = tool("adminDelete", adminCalls);

        contextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            ToolAuthorizationChatClientFactory factory = context.getBean(
                    ToolAuthorizationChatClientFactory.class);
            ChatClient protectedClient = factory.builder(context.getBean(OpenAiChatModel.class))
                    .defaultTools(customer, admin).build();
            ChatClient ordinaryClient = context.getBean(ChatClient.Builder.class)
                    .defaultTools(customer, admin).build();

            authenticate();
            assertThat(protectedClient.prompt().user("Run the lookup").call().content()).isEqualTo("done");
            SecurityContextHolder.clearContext();
            assertThat(ordinaryClient.prompt().user("Run the admin operation").call().content()).isEqualTo("done");

            assertThat(this.modelRequests).hasSize(4);
            assertThat(this.modelRequests.get(0)).contains("customerLookup").doesNotContain("adminDelete");
            assertThat(this.modelRequests.get(2)).contains("customerLookup", "adminDelete");
            assertThat(customerCalls).hasValue(1);
            assertThat(adminCalls).hasValue(1);
        });
    }

    @Test
    void aConfiguredClientStillRejectsMissingAuthenticationBeforeCallingTheModel() throws IOException {
        startModelServer(finalResponse());
        contextRunner().run(context -> {
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class))
                    .defaultTools(tool("customerLookup", new AtomicInteger())).build();

            assertThatThrownBy(() -> client.prompt().user("Run the lookup").call().content())
                    .isInstanceOf(AuthorizationDeniedException.class);
            assertThat(this.modelRequests).isEmpty();
        });
    }

    @Test
    void aModelRequestedHiddenToolCannotReachResolverFallback() throws IOException {
        startModelServer(toolResponse("adminDelete"));
        AtomicInteger adminCalls = new AtomicInteger();
        ToolCallbackResolver resolver = mock(ToolCallbackResolver.class);
        when(resolver.resolve("adminDelete")).thenReturn(tool("adminDelete", adminCalls));
        contextRunner().withPropertyValues("spring.ai.tools.resolution.fallback.enabled=true")
                .withBean(ToolCallbackResolver.class, () -> resolver)
                .run(context -> {
                    ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                            .builder(context.getBean(OpenAiChatModel.class))
                            .defaultTools(tool("customerLookup", new AtomicInteger()))
                            .build();
                    authenticate();

                    assertThatThrownBy(() -> client.prompt().user("Run an operation").call().content())
                            .isInstanceOf(AuthorizationDeniedException.class);
                    assertThat(this.modelRequests).singleElement().asString().doesNotContain("adminDelete");
                    assertThat(adminCalls).hasValue(0);
                    verify(resolver, never()).resolve(any());
                });
    }

    @Test
    void denyingEveryConfiguredToolSendsNoDefinitionsToTheModel() throws IOException {
        startModelServer(finalResponse());
        contextRunner().run(context -> {
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class))
                    .defaultTools(tool("adminDelete", new AtomicInteger())).build();
            authenticate();

            assertThat(client.prompt().user("Say hello").call().content()).isEqualTo("done");
            assertThat(this.modelRequests).singleElement().asString().doesNotContain("adminDelete");
        });
    }

    @Test
    @SuppressWarnings("removal") // Verify the provider's model-level default tool path in Spring AI 2.0.x.
    void anEmptyAuthorizedListDoesNotRestoreTheSharedModelsDefaultTools() throws IOException {
        startModelServer(finalResponse());
        contextRunner().run(context -> {
            OpenAiChatModel modelWithDefaultTools = OpenAiChatModel.builder()
                    .options(OpenAiChatOptions.builder().apiKey("test-api-key")
                            .baseUrl("http://127.0.0.1:" + this.server.getAddress().getPort())
                            .toolCallbacks(tool("adminDelete", new AtomicInteger())).build())
                    .toolCallingManager(context.getBean(ToolCallingManager.class)).build();
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(modelWithDefaultTools).build();
            authenticate();

            assertThat(client.prompt().user("Say hello").call().content()).isEqualTo("done");
            assertThat(this.modelRequests).singleElement().asString().doesNotContain("adminDelete");
        });
    }

    @Test
    void combinedPrivacyAndAuthorizationRestoreOnlyTheAuthorizedToolsInput() throws IOException {
        startModelServer(toolResponse("customerLookup", "{\\\"id\\\":\\\"EMP-0042\\\"}"), finalResponse());
        AtomicReference<String> customerInput = new AtomicReference<>();
        AtomicInteger adminCalls = new AtomicInteger();
        privacyContextRunner().run(context -> {
            PrivacyToolCallbackFactory factory = context.getBean(PrivacyToolCallbackFactory.class);
            ChatClient client = context.getBean(PrivacySecurityChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class))
                    .defaultTools(factory.wrap(tool("customerLookup", customerInput::set)),
                            factory.wrap(tool("adminDelete", adminCalls))).build();
            authenticate();

            assertThat(client.prompt().user("Find EMP-0042").call().content()).isEqualTo("done");
            assertThat(customerInput).hasValue("{\"id\":\"EMP-0042\"}");
            assertThat(adminCalls).hasValue(0);
            assertThat(this.modelRequests).allSatisfy(request ->
                    assertThat(request).doesNotContain("EMP-0042", "adminDelete"));
        });
    }

    @Test
    void losingTheAuthorizationHandleDoesNotTurnAProtectedRequestIntoAnOrdinaryRequest() throws IOException {
        startModelServer(finalResponse());
        contextRunner().run(context -> {
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class))
                    .defaultAdvisors(new RemoveToolContextAdvisor())
                    .defaultTools(tool("customerLookup", new AtomicInteger())).build();
            authenticate();

            assertThatThrownBy(() -> client.prompt().user("Run the lookup").call().content())
                    .isInstanceOf(AuthorizationDeniedException.class)
                    .hasMessageContaining("session handle");
            assertThat(this.modelRequests).isEmpty();
        });
    }

    @Test
    void toolSearchIndexesOnlyAuthorizedToolsAndExecutesThroughTheSelectedBoundary() throws IOException {
        startModelServer(toolResponse("toolSearchTool", "{\\\"SEARCH_QUERY_PARAMETER\\\":\\\"Find customer\\\"}"),
                toolResponse("customerLookup"), finalResponse());
        ToolIndex index = mock(ToolIndex.class);
        when(index.search(any())).thenReturn(ToolSearchResponse.builder()
                .addToolReference(ToolReference.builder().toolName("customerLookup")
                        .summary("Find customer").build()).build());
        AtomicInteger customerCalls = new AtomicInteger();
        AtomicInteger adminCalls = new AtomicInteger();

        contextRunner().run(context -> {
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class),
                            ToolSearchToolCallingAdvisor.builder().toolIndex(index)
                                    .systemMessageSuffix("Search for tools before using them."))
                    .defaultTools(tool("customerLookup", customerCalls), tool("adminDelete", adminCalls))
                    .build();
            authenticate();

            assertThat(client.prompt().advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "search-session"))
                    .user("Find customer").call().content()).isEqualTo("done");
            verify(index).indexTools(eq("search-session"), argThat(references ->
                    references.stream().map(ToolReference::toolName).toList().equals(List.of("customerLookup"))));
            assertThat(this.modelRequests.get(0)).contains("toolSearchTool")
                    .doesNotContain("customerLookup", "adminDelete");
            assertThat(this.modelRequests.get(1)).contains("customerLookup").doesNotContain("adminDelete");
            assertThat(customerCalls).hasValue(1);
            assertThat(adminCalls).hasValue(0);
        });
    }

    @Test
    void aSeparatelyRegisteredToolSearchAdvisorIsRejectedBeforeItCanIndexTools() throws IOException {
        startModelServer(finalResponse());
        ToolIndex index = mock(ToolIndex.class);
        contextRunner().run(context -> {
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class))
                    .defaultAdvisors(ToolSearchToolCallingAdvisor.builder().toolIndex(index)
                            .systemMessageSuffix("Search for tools before using them.").build())
                    .defaultTools(tool("adminDelete", new AtomicInteger())).build();
            authenticate();

            assertThatThrownBy(() -> client.prompt().user("Find tool").call().content())
                    .isInstanceOf(AuthorizationDeniedException.class).hasMessageContaining("managed tool advisor");
            verifyNoInteractions(index);
            assertThat(this.modelRequests).isEmpty();
        });
    }

    @Test
    void combinedToolSearchProtectsPiiAcrossSearchAndBusinessToolCalls() throws IOException {
        verifyToolSearchWithPrivacyAndAuthorization(false);
    }

    @Test
    void streamingCombinedToolSearchProtectsPiiAcrossSearchAndBusinessToolCalls() throws IOException {
        verifyToolSearchWithPrivacyAndAuthorization(true);
    }

    @Test
    void aStreamingToolLoopKeepsAuthorizationOnTheSelectedClient() throws IOException {
        startModelServer(toolStreamResponse("customerLookup"), finalStreamResponse(),
                toolStreamResponse("adminDelete"), finalStreamResponse());
        AtomicInteger customerCalls = new AtomicInteger();
        AtomicInteger adminCalls = new AtomicInteger();
        contextRunner().run(context -> {
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class))
                    .defaultTools(tool("customerLookup", customerCalls), tool("adminDelete", adminCalls)).build();
            authenticate();
            assertThat(client.prompt().user("Run lookup").stream().content()
                    .collectList().block(Duration.ofSeconds(10))).containsExactly("done");

            SecurityContextHolder.clearContext();
            ChatClient ordinary = context.getBean(ChatClient.Builder.class)
                    .defaultTools(tool("adminDelete", adminCalls)).build();
            assertThat(ordinary.prompt().user("Run operation").stream().content()
                    .collectList().block(Duration.ofSeconds(10))).containsExactly("done");
            assertThat(this.modelRequests.get(0)).contains("customerLookup").doesNotContain("adminDelete");
            assertThat(customerCalls).hasValue(1);
            assertThat(adminCalls).hasValue(1);
        });
    }

    @Test
    void downstreamMemoryDoesNotDuplicateTheCurrentTurnInTheToolLoop() throws IOException {
        verifyDownstreamMemory(false);
    }

    @Test
    void downstreamMemoryDoesNotDuplicateTheCurrentTurnInAStreamingToolLoop() throws IOException {
        verifyDownstreamMemory(true);
    }

    @Test
    void oversizedToolResponsesAreRejectedBeforeAnyBusinessToolExecutes() throws IOException {
        startModelServer(toolResponse("customerLookup", "{\\\"payload\\\":\\\"" + "x".repeat(200) + "\\\"}"), finalResponse());
        AtomicInteger calls = new AtomicInteger();
        privacyContextRunner().withPropertyValues("spring.ai.privacy.response-inspection.max-characters=64")
                .run(context -> {
                    PrivacyToolCallbackFactory factory = context.getBean(PrivacyToolCallbackFactory.class);
                    ChatClient client = context.getBean(PrivacySecurityChatClientFactory.class)
                            .builder(context.getBean(OpenAiChatModel.class))
                            .defaultTools(factory.wrap(tool("customerLookup", calls))).build();
                    authenticate();

                    assertThatThrownBy(() -> client.prompt().user("Run lookup").call().content())
                            .hasMessageContaining("inspection limit");
                    assertThat(calls).hasValue(0);
                    assertThat(this.modelRequests).hasSize(1);
                });
    }

    private void verifyToolSearchWithPrivacyAndAuthorization(boolean streaming) throws IOException {
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
            ChatClient client = context.getBean(PrivacySecurityChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class),
                            ToolSearchToolCallingAdvisor.builder().toolIndex(index)
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
            assertThat(this.modelRequests).hasSize(3).allSatisfy(modelRequest ->
                    assertThat(modelRequest).doesNotContain("EMP-0042", "adminDelete"));
        });
    }

    private void verifyDownstreamMemory(boolean streaming) throws IOException {
        if (streaming) {
            startModelServer(toolStreamResponse("customerLookup"), finalStreamResponse());
        } else {
            startModelServer(toolResponse("customerLookup"), finalResponse());
        }
        ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(20).build();
        memory.add("memory-session", List.of(new UserMessage("Previous question"),
                new AssistantMessage("Previous answer")));
        contextRunner().run(context -> {
            ChatClient client = context.getBean(ToolAuthorizationChatClientFactory.class)
                    .builder(context.getBean(OpenAiChatModel.class))
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).order(0).build())
                    .defaultTools(tool("customerLookup", new AtomicInteger())).build();
            authenticate();

            var request = client.prompt().user("Run current lookup")
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "memory-session"));
            String response = executeRequest(request, streaming);
            assertThat(response).isEqualTo("done");

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

}
