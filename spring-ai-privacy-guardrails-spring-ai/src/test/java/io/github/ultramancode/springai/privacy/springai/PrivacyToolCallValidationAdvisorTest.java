package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrivacyToolCallValidationAdvisorTest {

    @Test
    void defaultOrderIsRecommendedAndCustomOrdersAreApplicationOwned() {
        PrivacyService service = TestPrivacyServices.privacyService();

        assertThat(new PrivacyToolCallValidationAdvisor(service).getOrder())
                .isEqualTo(ToolCallingAdvisor.DEFAULT_ORDER + 1);
        assertThat(new PrivacyToolCallValidationAdvisor(service, 1).getOrder()).isEqualTo(1);
        assertThat(new PrivacyToolCallValidationAdvisor(service, Integer.MIN_VALUE).getOrder())
                .isEqualTo(Integer.MIN_VALUE);
        assertThat(new PrivacyToolCallValidationAdvisor(service, Integer.MAX_VALUE).getOrder())
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void callRejectsToolCallInjectedAfterTheModelBoundary() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeToolRequest(session, service);
            when(chain.nextCall(any())).thenReturn(validatedResponse(
                    request,
                    toolCallResponse("call-1", "Alice"),
                    Set.of("customerLookup")
            ));

            assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                        assertThat(failure).hasMessage("Tool control field rejected by privacy guardrail")
                                .hasMessageNotContaining("Alice");
                    });
        }
    }

    @Test
    void callRejectsExplicitLimitsThatConflictWithOuterOutputLimitsBeforeModelInvocation() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyResponseInspectionLimits toolLimits = new PrivacyResponseInspectionLimits(
                10,
                1_000,
                1_000,
                Duration.ofSeconds(1)
        );
        PrivacyResponseInspectionLimits outputLimits = new PrivacyResponseInspectionLimits(
                20,
                2_000,
                2_000,
                Duration.ofSeconds(2)
        );
        PrivacyToolCallValidationAdvisor advisor =
                new PrivacyToolCallValidationAdvisor(service, toolLimits);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = PrivacyOutputContextSupport.attachResponseInspectionLimits(
                    activeToolRequest(session, service),
                    outputLimits
            );

            assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOOL_INPUT);
                        assertThat(failure)
                                .hasMessage("Conflicting response inspection limits are configured for tool execution");
                    });
            verifyNoInteractions(chain);
        }
    }

    @Test
    void callAllowsExplicitLimitsThatMatchOuterOutputLimits() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyResponseInspectionLimits limits = new PrivacyResponseInspectionLimits(
                10,
                1_000,
                1_000,
                Duration.ofSeconds(1)
        );
        PrivacyToolCallValidationAdvisor advisor =
                new PrivacyToolCallValidationAdvisor(service, limits);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = PrivacyOutputContextSupport.attachResponseInspectionLimits(
                    activeToolRequest(session, service),
                    limits
            );
            when(chain.nextCall(any())).thenReturn(validatedResponse(
                    request,
                    new ChatResponse(List.of(new Generation(new AssistantMessage("safe response")))),
                    Set.of("customerLookup")
            ));

            assertThat(advisor.adviseCall(request, chain).chatResponse()).isNotNull();
        }
    }

    @Test
    void streamAllowsOpaqueMetadataToolCallIdsOwnedByTheProvider() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(session);
            ChatResponse metadataToolCall = metadataToolCallResponse(
                    List.of(new AssistantMessage.ToolCall("Alice", "function", "customerLookup", "{}"))
            );
            when(chain.nextStream(any())).thenReturn(Flux.just(validatedResponse(
                    request,
                    metadataToolCall,
                    Set.of("customerLookup")
            )));

            assertThat(advisor.adviseStream(request, chain).collectList().block()).hasSize(1);
        }
    }

    @Test
    void streamDoesNotApplyOrCompareToolExecutionLimitsWhenNoToolsAreRegistered() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyResponseInspectionLimits limits = new PrivacyResponseInspectionLimits(
                2,
                10,
                10,
                Duration.ofMillis(10)
        );
        PrivacyToolCallValidationAdvisor advisor =
                new PrivacyToolCallValidationAdvisor(service, limits);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = PrivacyOutputContextSupport.attachResponseInspectionLimits(
                    activeRequest(session),
                    PrivacyResponseInspectionLimits.defaults()
            );
            ChatClientResponse response = validatedResponse(
                    request,
                    new ChatResponse(List.of(new Generation(new AssistantMessage("safe response")))),
                    Set.of()
            );
            when(chain.nextStream(any())).thenReturn(Flux.just(response, response, response));

            assertThat(advisor.adviseStream(request, chain).collectList().block()).hasSize(3);
        }
    }

    @Test
    void streamRejectsMalformedToolCallMetadata() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(session);
            ChatResponse malformed = new ChatResponse(
                    List.of(new Generation(new AssistantMessage(""))),
                    ChatResponseMetadata.builder().keyValue("toolCalls", List.of("not-a-tool-call")).build()
            );
            when(chain.nextStream(any())).thenReturn(Flux.just(validatedResponse(
                    request,
                    malformed,
                    Set.of("customerLookup")
            )));

            assertThatThrownBy(() -> advisor.adviseStream(request, chain).collectList().block())
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                        assertThat(failure).hasMessage("Streaming response tool-call metadata is invalid");
                    });
        }
    }

    @Test
    void callRejectsNullMessageToolCallWithTypedSafeFailure() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(session);
            List<AssistantMessage.ToolCall> malformedCalls = new ArrayList<>();
            malformedCalls.add(null);
            AssistantMessage malformedMessage = AssistantMessage.builder()
                    .content("")
                    .toolCalls(malformedCalls)
                    .build();
            when(chain.nextCall(any())).thenReturn(validatedResponse(
                    request,
                    new ChatResponse(List.of(new Generation(malformedMessage))),
                    Set.of("customerLookup")
            ));

            assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                        assertThat(failure).hasMessage("Tool control field is invalid");
                    });
        }
    }

    @Test
    void streamLeavesMetadataToolCallCountToTheApplication() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(session);
            List<AssistantMessage.ToolCall> calls = IntStream.range(0, 300)
                    .mapToObj(index -> new AssistantMessage.ToolCall(
                            Integer.toString(index), "function", "customerLookup", "{}"
                    ))
                    .toList();
            when(chain.nextStream(any())).thenReturn(Flux.just(validatedResponse(
                    request,
                    metadataToolCallResponse(calls),
                    Set.of("customerLookup")
            )));

            assertThat(advisor.adviseStream(request, chain).collectList().block()).hasSize(1);
        }
    }

    @Test
    void callLeavesMessageToolCallCountToTheApplication() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(session);
            List<AssistantMessage.ToolCall> calls = IntStream.range(0, 300)
                    .mapToObj(index -> new AssistantMessage.ToolCall(
                            Integer.toString(index), "function", "customerLookup", "{}"
                    ))
                    .toList();
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(calls)
                    .build();
            when(chain.nextCall(any())).thenReturn(validatedResponse(
                    request,
                    new ChatResponse(List.of(new Generation(message))),
                    Set.of("customerLookup")
            ));

            assertThat(advisor.adviseCall(request, chain).chatResponse()).isNotNull();
        }
    }

    @Test
    void callAllowsRepeatedBlankIdToolCallsWithinMessageChannel() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(session);
            AssistantMessage.ToolCall first = new AssistantMessage.ToolCall(
                    "", "function", "customerLookup", "{\"id\":\"CUST-1\"}"
            );
            AssistantMessage.ToolCall second = new AssistantMessage.ToolCall(
                    "", "function", "customerLookup", "{\"id\":\"CUST-1\"}"
            );
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(first, second))
                    .build();
            when(chain.nextCall(any())).thenReturn(validatedResponse(
                    request,
                    new ChatResponse(List.of(new Generation(message))),
                    Set.of("customerLookup")
            ));

            ChatClientResponse response = advisor.adviseCall(request, chain);

            assertThat(response.chatResponse().getResult().getOutput().getToolCalls()).hasSize(2);
        }
    }

    @Test
    void streamAllowsRepeatedBlankIdToolCallsWithinMetadataChannel() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(session);
            AssistantMessage.ToolCall first = new AssistantMessage.ToolCall(
                    "", "function", "customerLookup", "{\"id\":\"CUST-1\"}"
            );
            AssistantMessage.ToolCall second = new AssistantMessage.ToolCall(
                    "", "function", "customerLookup", "{\"id\":\"CUST-1\"}"
            );
            when(chain.nextStream(any())).thenReturn(Flux.just(validatedResponse(
                    request,
                    metadataToolCallResponse(List.of(first, second)),
                    Set.of("customerLookup")
            )));

            assertThat(advisor.adviseStream(request, chain).collectList().block()).hasSize(1);
        }
    }

    @Test
    void streamRejectsDuplicateToolCallAcrossMessageAndMetadataChannels() {
        assertStreamRejectsCrossChannelDuplicate("call-1");
    }

    @Test
    void streamRejectsDuplicateBlankIdToolCallAcrossMessageAndMetadataChannels() {
        assertStreamRejectsCrossChannelDuplicate("");
    }

    private void assertStreamRejectsCrossChannelDuplicate(String id) {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeToolRequest(session, service);
            AssistantMessage.ToolCall duplicate = new AssistantMessage.ToolCall(
                    id, "function", "customerLookup", "{}"
            );
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(duplicate))
                    .build();
            ChatResponse response = new ChatResponse(
                    List.of(new Generation(message)),
                    ChatResponseMetadata.builder().keyValue("toolCalls", List.of(duplicate)).build()
            );
            when(chain.nextStream(any())).thenReturn(Flux.just(validatedResponse(
                    request,
                    response,
                    Set.of("customerLookup")
            )));

            assertThatThrownBy(() -> advisor.adviseStream(request, chain).collectList().block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("Duplicate tool call rejected by privacy guardrail");
        }
    }

    @Test
    void streamRejectsSplitToolNamesBecauseSpringTreatsEachAsACompleteCall() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(session);
            when(chain.nextStream(any())).thenReturn(Flux.just(
                    validatedResponse(request, toolCallResponse("call-1", "customer"),
                            Set.of("customerLookup")),
                    validatedResponse(request, toolCallResponse("call-1", "Lookup"),
                            Set.of("customerLookup"))
            ));

            assertThatThrownBy(() -> advisor.adviseStream(request, chain).collectList().block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("Model requested a tool outside the registered privacy boundary");
        }
    }

    @Test
    void protectsOnlyToolSearchArgumentsAndPreservesProviderFields() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        String searchArguments = "{\"query\":\"Find Alice\",\"maxResults\":3,\"categoryFilter\":\"sales\"}";
        AssistantMessage.ToolCall search = new AssistantMessage.ToolCall(
                "search-1", "function", "toolSearchTool", searchArguments
        );
        AssistantMessage.ToolCall business = new AssistantMessage.ToolCall(
                "business-1", "function", "customerLookup", "{\"name\":\"Alice\"}"
        );
        DeepSeekAssistantMessage message = new DeepSeekAssistantMessage.Builder()
                .content("unchanged content")
                .reasoningContent("unchanged reasoning")
                .prefix(true)
                .properties(Map.of("provider-field", "kept"))
                .toolCalls(List.of(search, business))
                .build();
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason("tool_calls").build();

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeToolSearchRequest(session, service, true);
            ChatClientResponse response = validatedResponse(request,
                    new ChatResponse(List.of(new Generation(message, generationMetadata))),
                    Set.of("toolSearchTool", "customerLookup"));
            when(chain.nextCall(any())).thenReturn(response);

            ChatClientResponse protectedResponse = advisor.adviseCall(request, chain);

            DeepSeekAssistantMessage protectedMessage = (DeepSeekAssistantMessage)
                    protectedResponse.chatResponse().getResult().getOutput();
            assertThat(protectedMessage.getText()).isEqualTo(message.getText());
            assertThat(protectedMessage.getReasoningContent()).isEqualTo(message.getReasoningContent());
            assertThat(protectedMessage.getPrefix()).isTrue();
            assertThat(protectedMessage.getMetadata()).isEqualTo(message.getMetadata());
            assertThat(protectedMessage.getToolCalls().get(1)).isSameAs(business);
            AssistantMessage.ToolCall protectedCall = protectedMessage.getToolCalls().get(0);
            assertThat(protectedCall.id()).isEqualTo(search.id());
            assertThat(protectedCall.type()).isEqualTo(search.type());
            assertThat(protectedCall.name()).isEqualTo(search.name());
            assertThat(protectedCall.arguments()).doesNotContain("Alice")
                    .contains("\"maxResults\":3", "\"categoryFilter\":\"sales\"");
            assertThat(service.detokenize(session.handle(), protectedCall.arguments())).isEqualTo(searchArguments);
            assertThat(protectedResponse.chatResponse().getResult().getMetadata()).isSameAs(generationMetadata);
            assertThat(protectedResponse.chatResponse().getMetadata()).isSameAs(response.chatResponse().getMetadata());
            assertThat(protectedResponse.context()).isEqualTo(response.context());
            assertThat(search.arguments()).isEqualTo(searchArguments);
        }
    }

    @Test
    void protectsMetadataToolSearchArgumentsWithoutLosingResponseMetadata() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        AssistantMessage.ToolCall search = new AssistantMessage.ToolCall(
                "search-1", "function", "toolSearchTool", "{\"query\":\"Alice\"}"
        );
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .id("response-id").model("test-model")
                .keyValue("provider-field", "kept")
                .keyValue("toolCalls", List.of(search))
                .build();
        Generation generation = new Generation(new AssistantMessage("unchanged"));

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeToolSearchRequest(session, service, true);
            ChatClientResponse response = validatedResponse(request,
                    new ChatResponse(List.of(generation), metadata), Set.of("toolSearchTool"));
            when(chain.nextStream(any())).thenReturn(Flux.just(response));

            ChatResponse protectedResponse = advisor.adviseStream(request, chain).blockLast().chatResponse();

            List<AssistantMessage.ToolCall> calls = PrivacyToolCallMetadataReader.read(
                    protectedResponse, PrivacyPhase.TOOL_INPUT);
            assertThat(calls).hasSize(1);
            assertThat(calls.get(0).arguments()).doesNotContain("Alice");
            assertThat(service.detokenize(session.handle(), calls.get(0).arguments())).isEqualTo(search.arguments());
            assertThat(protectedResponse.getResult()).isSameAs(generation);
            assertThat(protectedResponse.getMetadata().getId()).isEqualTo(metadata.getId());
            assertThat(protectedResponse.getMetadata().getModel()).isEqualTo(metadata.getModel());
            assertThat(protectedResponse.getMetadata().getUsage()).isSameAs(metadata.getUsage());
            assertThat(protectedResponse.getMetadata().getRateLimit()).isSameAs(metadata.getRateLimit());
            assertThat(protectedResponse.getMetadata().getPromptMetadata()).isSameAs(metadata.getPromptMetadata());
            assertThat((String) protectedResponse.getMetadata().get("provider-field")).isEqualTo("kept");
            assertThat(PrivacyToolCallMetadataReader.read(response.chatResponse(), PrivacyPhase.TOOL_INPUT))
                    .containsExactly(search);
        }
    }

    @Test
    void preservesSafeAndAlreadyTokenizedSearchArguments() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeToolSearchRequest(session, service, true);
            for (String query : List.of("customer lookup", service.tokenize(session.handle(), "Alice"))) {
                AssistantMessage message = AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "search-1", "function", "toolSearchTool", "{\"query\":\"" + query + "\"}"
                        ))).build();
                ChatClientResponse response = validatedResponse(request,
                        new ChatResponse(List.of(new Generation(message))), Set.of("toolSearchTool"));
                when(chain.nextCall(any())).thenReturn(response);

                assertThat(advisor.adviseCall(request, chain)).isSameAs(response);
            }
        }
    }

    @Test
    void leavesAWrappedToolWithTheReservedNameToItsDisclosurePolicyWithoutTheSearchMarker() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallValidationAdvisor advisor = new PrivacyToolCallValidationAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeToolSearchRequest(session, service, false);
            AssistantMessage message = AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            "business-1", "function", "toolSearchTool", "{\"query\":\"Alice\"}"
                    ))).build();
            ChatClientResponse response = validatedResponse(request,
                    new ChatResponse(List.of(new Generation(message))), Set.of("toolSearchTool"));
            when(chain.nextCall(any())).thenReturn(response);

            assertThat(advisor.adviseCall(request, chain)).isSameAs(response);
        }
    }

    private ChatClientRequest activeToolSearchRequest(
            PrivacySession session,
            PrivacyService service,
            boolean withSessionMarker
    ) {
        ToolCallback search = mock(ToolCallback.class);
        when(search.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("toolSearchTool").description("Search tools").inputSchema("{}").build());
        when(search.getToolMetadata()).thenReturn(ToolMetadata.builder().build());
        if (!withSessionMarker) {
            search = new PrivacyToolCallbackFactory(service, ToolDisclosurePolicy.denyAll()).wrap(search);
        }
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(search))
                .toolContext(withSessionMarker ? Map.of("toolSearchToolSessionId", "search-session") : Map.of())
                .build();
        return PrivacyRequestContextSupport.attachLifecycle(
                new ChatClientRequest(new Prompt("hello", options), Map.of()), session.handle());
    }

    private ChatClientRequest activeRequest(PrivacySession session) {
        return PrivacyRequestContextSupport.attachLifecycle(
                new ChatClientRequest(new Prompt("hello"), Map.of()),
                session.handle()
        );
    }

    private ChatClientRequest activeToolRequest(PrivacySession session, PrivacyService service) {
        ToolCallback callback = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("customerLookup")
                        .description("lookup")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String input) {
                return "safe";
            }
        };
        ToolCallback wrapped = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        ).wrap(callback);
        Prompt prompt = new Prompt(
                "hello",
                ToolCallingChatOptions.builder().toolCallbacks(List.of(wrapped)).build()
        );
        return PrivacyRequestContextSupport.attachLifecycle(
                new ChatClientRequest(prompt, Map.of()),
                session.handle()
        );
    }

    private ChatClientResponse validatedResponse(
            ChatClientRequest request,
            ChatResponse response,
            Set<String> registeredToolNames
    ) {
        ChatClientRequest validated = PrivacyToolExecutionContextSupport.attachRegisteredToolNames(
                request,
                registeredToolNames
        );
        return new ChatClientResponse(response, validated.context());
    }

    private ChatResponse toolCallResponse(String id, String name) {
        AssistantMessage message = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(id, "function", name, "{}")))
                .build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatResponse metadataToolCallResponse(List<AssistantMessage.ToolCall> toolCalls) {
        return new ChatResponse(
                List.of(new Generation(new AssistantMessage(""))),
                ChatResponseMetadata.builder().keyValue("toolCalls", toolCalls).build()
        );
    }
}
