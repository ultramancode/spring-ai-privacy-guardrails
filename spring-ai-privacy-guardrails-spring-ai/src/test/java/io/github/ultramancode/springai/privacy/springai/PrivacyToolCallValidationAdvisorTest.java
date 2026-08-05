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
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
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
