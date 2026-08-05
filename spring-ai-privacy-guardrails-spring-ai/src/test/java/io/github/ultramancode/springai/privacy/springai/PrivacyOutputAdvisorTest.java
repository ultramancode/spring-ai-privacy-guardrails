package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivacyOutputAdvisorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void defaultOrderIsFixedBeforeTheFinalModelBoundary() {
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(privacyService());

        assertThat(advisor.getOrder()).isEqualTo(PrivacyOutputAdvisor.DEFAULT_ORDER)
                .isLessThan(PrivacyToolContextAdvisor.DEFAULT_ORDER)
                .isLessThan(ToolCallingAdvisor.DEFAULT_ORDER)
                .isLessThan(PrivacyModelBoundaryAdvisor.DEFAULT_ORDER);
        assertThat(new PrivacyOutputAdvisor(
                privacyService(),
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                new PrivacyResponseInspectionLimits(1, 1, 1, Duration.ofSeconds(1)),
                123
        ).getOrder()).isEqualTo(123);
    }

    @Test
    void adviseCallRejectsMissingInputSessionBeforeModelCall() {
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(privacyService());
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        assertThatThrownBy(() -> advisor.adviseCall(request(), chain))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("requires an active request privacy session");
        verify(chain, never()).nextCall(any());
    }

    @Test
    void adviseCallRejectsClosedContextBeforeModelCall() {
        PrivacyService service = privacyService();
        PrivacyContextHandle handle;
        try (PrivacySession session = service.openSession()) {
            handle = session.handle();
        }
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientRequest request = new ChatClientRequest(
                new Prompt("hello"),
                Map.of(PrivacyRequestContextSupport.CONTEXT_HANDLE, handle)
        );

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("closed");
        verify(chain, never()).nextCall(any());
    }

    @Test
    void adviseCallTokenizesDetectedOutput() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PrivacyService service = privacyService(analysisCalls);
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked"
        );
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response("Alice is available"));

        ChatClientResponse result = adviseCallInSession(service, advisor, chain);

        assertThat(result.chatResponse().getResult().getOutput().getText())
                .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern() + " is available");
        assertThat(analysisCalls.get()).isEqualTo(1);
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void applicationBoundaryProtectsProviderTextInMessageAndGenerationMetadata() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        List<byte[]> thoughtSignatures = List.of(new byte[]{1, 2, 3});
        AssistantMessage message = AssistantMessage.builder()
                .content("safe")
                .properties(Map.of(
                        "reasoningContent", "Alice reviewed the request",
                        "thoughtSignatures", thoughtSignatures
                ))
                .build();
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason("stop")
                .metadata("thinking", "Alice considered the request")
                .metadata("provider-count", 7)
                .build();
        ChatClientResponse raw = response(List.of(new Generation(message, generationMetadata)));

        try (PrivacySession session = service.openSession()) {
            ChatClientResponse result = advisor.protectAtApplicationBoundary(session.handle(), raw);
            Generation generation = result.chatResponse().getResult();

            assertThat(generation.getOutput().getMetadata().get("reasoningContent").toString())
                    .doesNotContain("Alice")
                    .contains("[[PII_PERSON_");
            assertThat(generation.getOutput().getMetadata().get("thoughtSignatures"))
                    .isSameAs(thoughtSignatures);
            assertThat(generation.getMetadata().get("thinking").toString())
                    .doesNotContain("Alice")
                    .contains("[[PII_PERSON_");
            assertThat((Object) generation.getMetadata().get("provider-count")).isEqualTo(7);
            assertThat(generation.getMetadata().getFinishReason()).isEqualTo("stop");
        }
    }

    @Test
    void applicationBoundaryBlocksPiiStoredOnlyInProviderMetadata() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.BLOCK,
                "blocked"
        );
        AssistantMessage message = AssistantMessage.builder()
                .content("safe")
                .properties(Map.of("reasoningContent", "Alice is hidden"))
                .build();

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> advisor.protectAtApplicationBoundary(
                    session.handle(),
                    response(List.of(new Generation(message)))
            ))
                    .isInstanceOf(PrivacyOutputBlockedException.class)
                    .hasMessage("blocked");
        }
    }

    @Test
    void returnDirectGenerationRestoresStructuredToolTokensBeforeRedaction() throws Exception {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.REDACT,
                "blocked"
        );
        String rawToolResult = "{\"phone\":821012345678,\"owner\":\"Alice\"}";

        try (PrivacySession session = service.openSession()) {
            String tokenizedToolResult = PrivacyJsonPayloadTransformer.tokenize(
                    service,
                    session.handle(),
                    rawToolResult,
                    PrivacyPhase.TOOL_OUTPUT,
                    true
            );
            Generation returnDirectGeneration = new Generation(
                    new AssistantMessage(tokenizedToolResult),
                    ChatGenerationMetadata.builder()
                            .finishReason(ToolExecutionResult.FINISH_REASON)
                            .build()
            );

            ChatClientResponse protectedResponse = advisor.protectAtApplicationBoundary(
                    session.handle(),
                    response(List.of(returnDirectGeneration))
            );
            String result = protectedResponse.chatResponse().getResult().getOutput().getText();

            assertThat(OBJECT_MAPPER.readValue(result, Object.class)).isNotNull();
            assertThat(result)
                    .contains("[REDACTED_PHONE_NUMBER]", "[REDACTED_PERSON]")
                    .doesNotContain("821012345678", "Alice", "[[PII_");
        }
    }

    @Test
    void returnDirectGenerationProtectsBracketShapedPlainText() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.REDACT,
                "blocked"
        );

        try (PrivacySession session = service.openSession()) {
            for (String rawToolResult : List.of(
                    "[INFO] Alice is available",
                    "{not-json Bob is available"
            )) {
                String tokenizedToolResult = PrivacyJsonPayloadTransformer.tokenize(
                        service,
                        session.handle(),
                        rawToolResult,
                        PrivacyPhase.TOOL_OUTPUT,
                        false
                );
                Generation returnDirectGeneration = new Generation(
                        new AssistantMessage(tokenizedToolResult),
                        ChatGenerationMetadata.builder()
                                .finishReason(ToolExecutionResult.FINISH_REASON)
                                .build()
                );

                ChatClientResponse protectedResponse = advisor.protectAtApplicationBoundary(
                        session.handle(),
                        response(List.of(returnDirectGeneration))
                );

                assertThat(protectedResponse.chatResponse().getResult().getOutput().getText())
                        .contains("[REDACTED_PERSON]")
                        .doesNotContain("Alice", "Bob", "[[PII_");
            }
        }
    }

    @Test
    void applicationBoundaryAppliesBufferLimitsToNonStreamingResponses() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                new PrivacyResponseInspectionLimits(1, 4, 1, Duration.ofSeconds(1))
        );

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> advisor.protectAtApplicationBoundary(
                    session.handle(),
                    response("Alice")
            )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                assertThat(failure.code())
                        .isEqualTo(PrivacyFailureCode.RESPONSE_INSPECTION_LIMIT_EXCEEDED);
                assertThat(failure.phase()).isEqualTo(PrivacyPhase.OUTPUT_POLICY);
            });
        }
    }

    @Test
    void adviseCallLeavesNormalResponseForTheLifecycleBoundary() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientResponse raw = response("Alice is available");
        when(chain.nextCall(any())).thenReturn(raw);

        try (PrivacySession session = service.openSession()) {
            ChatClientResponse result = advisor.adviseCall(request(session.handle()), chain);

            assertThat(result).isSameAs(raw);
            assertThat(result.chatResponse().getResult().getOutput().getText())
                    .isEqualTo("Alice is available");
        }
    }

    @Test
    void adviseCallBlocksDetectedOutputWhenConfigured() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.BLOCK,
                "blocked"
        );
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response("Alice is available"));

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> advisor.protectAtApplicationBoundary(
                    session.handle(),
                    advisor.adviseCall(request(session.handle()), chain)
            ))
                    .isInstanceOfSatisfying(PrivacyOutputBlockedException.class, failure -> {
                        assertThat(failure).hasMessage("blocked");
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.POLICY_BLOCKED);
                        assertThat(failure.phase()).isEqualTo(PrivacyPhase.OUTPUT_POLICY);
                    });
            assertThat(service.isSessionActive(session.handle())).isTrue();
        }
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void adviseCallRedactsDetectedOutputWhenConfigured() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.REDACT,
                "blocked"
        );
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response("Alice is available"));

        ChatClientResponse result = adviseCallInSession(service, advisor, chain);

        assertThat(result.chatResponse().getResult().getOutput().getText())
                .isEqualTo("[REDACTED_PERSON] is available");
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void allActionsIgnoreKnownOpaqueTokensEvenWhenAnalyzerMatchesTokenCharacters() {
        PrivacyService service = digitAwarePrivacyService();
        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), "Alice");

            for (PrivacyOutputAction action : PrivacyOutputAction.values()) {
                PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service, action, "blocked");
                CallAdvisorChain chain = mock(CallAdvisorChain.class);
                when(chain.nextCall(any())).thenReturn(response(token));

                ChatClientResponse result = advisor.protectAtApplicationBoundary(
                        session.handle(),
                        advisor.adviseCall(request(session.handle()), chain)
                );

                assertThat(result.chatResponse().getResult().getOutput().getText()).isEqualTo(token);
            }
        }
    }

    @Test
    void adviseCallPreservesAdditionalGenerations() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked"
        );
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(response(List.of(
                new Generation(new AssistantMessage("Alice is available")),
                new Generation(new AssistantMessage("Alice is also listed"))
        )));

        ChatClientResponse result = adviseCallInSession(service, advisor, chain);

        assertThat(result.chatResponse().getResults()).hasSize(2);
        String first = result.chatResponse().getResults().get(0).getOutput().getText();
        String second = result.chatResponse().getResults().get(1).getOutput().getText();
        assertThat(first).matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern() + " is available");
        assertThat(second).isEqualTo(first.replace(" is available", " is also listed"));
    }

    @Test
    void adviseCallTokenizesRawPiiInReturnedToolCallArguments() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1",
                        "function",
                        "lookup",
                        "{\"name\":\"Alice\"}"
                )))
                .build();
        when(chain.nextCall(any())).thenReturn(response(List.of(new Generation(toolCallMessage))));

        ChatClientResponse result = adviseCallInSession(service, advisor, chain);

        String arguments = result.chatResponse().getResult().getOutput().getToolCalls().get(0).arguments();
        assertThat(arguments)
                .containsPattern(OpaquePiiTokenFormat.patternForEntityType("PERSON"))
                .doesNotContain("Alice");
    }

    @Test
    void adviseCallPreservesProviderOwnedToolCallId() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "Alice",
                        "function",
                        "lookup",
                        "{}"
                )))
                .build();
        when(chain.nextCall(any())).thenReturn(response(List.of(new Generation(toolCallMessage))));

        try (PrivacySession session = service.openSession()) {
            ChatClientResponse protectedResponse = advisor.protectAtApplicationBoundary(
                    session.handle(),
                    advisor.adviseCall(request(session.handle()), chain)
            );

            assertThat(protectedResponse.chatResponse().getResult().getOutput().getToolCalls())
                    .singleElement()
                    .satisfies(toolCall -> assertThat(toolCall.id()).isEqualTo("Alice"));
        }
    }

    private PrivacyService privacyService() {
        return privacyService(new AtomicInteger());
    }

    private PrivacyService privacyService(AtomicInteger analysisCalls) {
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            int index = text.indexOf("Alice");
            if (index < 0) {
                return List.of();
            }
            return List.of(new PiiSpan("PERSON", index, index + 5, 0.95));
        };
        return new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
    }

    private PrivacyService digitAwarePrivacyService() {
        PiiAnalyzer analyzer = (text, options) -> {
            List<PiiSpan> spans = new ArrayList<>();
            int person = text.indexOf("Alice");
            if (person >= 0) {
                spans.add(new PiiSpan("PERSON", person, person + 5, 0.95));
            }
            for (int index = 0; index < text.length(); index++) {
                if (Character.isDigit(text.charAt(index))) {
                    spans.add(new PiiSpan("NATIONAL_ID", index, index + 1, 0.95));
                }
            }
            return List.copyOf(spans);
        };
        return new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
    }

    private ChatClientRequest request() {
        return new ChatClientRequest(new Prompt("hello"), Map.of());
    }

    private ChatClientRequest request(PrivacyContextHandle handle) {
        return new ChatClientRequest(
                new Prompt("hello"),
                Map.of(PrivacyRequestContextSupport.CONTEXT_HANDLE, handle)
        );
    }

    private ChatClientResponse adviseCallInSession(
            PrivacyService service,
            PrivacyOutputAdvisor advisor,
            CallAdvisorChain chain
    ) {
        try (PrivacySession session = service.openSession()) {
            ChatClientResponse response = advisor.protectAtApplicationBoundary(
                    session.handle(),
                    advisor.adviseCall(request(session.handle()), chain)
            );
            assertThat(service.isSessionActive(session.handle())).isTrue();
            return response;
        }
    }

    private ChatClientResponse response(String text) {
        return response(List.of(new Generation(new AssistantMessage(text))));
    }

    private ChatClientResponse response(List<Generation> generations) {
        return new ChatClientResponse(new ChatResponse(generations), Map.of());
    }

}
