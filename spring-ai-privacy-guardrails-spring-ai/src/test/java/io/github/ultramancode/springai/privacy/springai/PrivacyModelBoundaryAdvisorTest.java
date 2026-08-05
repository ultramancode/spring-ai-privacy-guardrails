package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.advisor.ChatModelCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrivacyModelBoundaryAdvisorTest {

    @Test
    void defaultOrderIsTheRecommendedLateModelBoundary() {
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(
                TestPrivacyServices.privacyService()
        );

        assertThat(advisor.getOrder()).isEqualTo(Integer.MAX_VALUE - 1);
        assertThat(new PrivacyModelBoundaryAdvisor(TestPrivacyServices.privacyService(), 123).getOrder())
                .isEqualTo(123);
    }

    @Test
    void advisorTokenizesContentAddedByDownstreamRagAdvisorBeforeModelCall() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(
                    new ChatClientRequest(
                            new Prompt(List.of(new UserMessage("Retrieved customer: Alice"))),
                            Map.of("rag", true)
                    ),
                    session.handle()
            );
            when(chain.nextCall(any())).thenAnswer(invocation -> {
                ChatClientRequest protectedRequest = invocation.getArgument(0);
                String text = protectedRequest.prompt().getUserMessage().getText();
                assertThat(text).doesNotContain("Alice");
                assertThat(service.detokenize(session.handle(), text))
                        .isEqualTo("Retrieved customer: Alice");
                return TestPrivacyServices.response("ok");
            });

            advisor.adviseCall(request, chain);
        }
    }

    @Test
    void advisorFailsClosedWhenInputSessionIsMissing() {
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(
                TestPrivacyServices.privacyService()
        );
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ChatClientRequest request = new ChatClientRequest(new Prompt("Alice"), Map.of());

        assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("PrivacyLifecycleAdvisor");
    }

    @Test
    void advisorAllowsAdditionalApplicationAdvisorAfterTheBoundary() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(service);
        CallAdvisor lateAdvisor = mock(CallAdvisor.class);
        when(lateAdvisor.getOrder()).thenReturn(PrivacyModelBoundaryAdvisor.DEFAULT_ORDER);
        ChatModelCallAdvisor modelAdvisor = mock(ChatModelCallAdvisor.class);
        when(modelAdvisor.getOrder()).thenReturn(Integer.MAX_VALUE);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.getCallAdvisors()).thenReturn(List.of(advisor, lateAdvisor, modelAdvisor));
        when(chain.nextCall(any())).thenAnswer(invocation -> {
            ChatClientRequest protectedRequest = invocation.getArgument(0);
            assertThat(protectedRequest.prompt().getUserMessage().getText())
                    .doesNotContain("Alice");
            return TestPrivacyServices.response("ok");
        });

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(
                    new ChatClientRequest(new Prompt("Alice"), Map.of()),
                    session.handle()
            );

            assertThat(advisor.adviseCall(request, chain)).isNotNull();
            verify(chain).nextCall(any());
        }
    }

    @Test
    void finalBoundaryRejectsLateToolReplacementFromAnotherFactory() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory expectedFactory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyToolCallbackFactory otherFactory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(service, expectedFactory);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(otherFactory.wrap(tool("customerLookup"))))
                .build();

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(
                    new ChatClientRequest(new Prompt("hello", options), Map.of()),
                    session.handle()
            );

            assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("PrivacyToolContextAdvisor rejected a tool callback from another privacy factory");
            verify(chain, never()).nextCall(any());
        }
    }

    @Test
    void finalBoundaryRejectsLateCallbackReplacementFromTheSameFactory() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(service, factory);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ToolCallingChatOptions originalOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(factory.wrap(tool("customerLookup"))))
                .build();
        ToolCallingChatOptions replacementOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(factory.wrap(tool("replacementLookup"))))
                .build();

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest snapshotted = activeRequest(
                    new ChatClientRequest(new Prompt("hello", originalOptions), Map.of()),
                    session.handle()
            );
            ChatClientRequest replaced = snapshotted.mutate()
                    .prompt(new Prompt(snapshotted.prompt().getInstructions(), replacementOptions))
                    .build();

            assertThatThrownBy(() -> advisor.adviseCall(replaced, chain))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("Tool callbacks changed after the privacy tool-context boundary");
            verify(chain, never()).nextCall(any());
        }
    }

    @Test
    void advisorRejectsPiiInHistoricalToolResponseNameBeforeModelCall() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(
                        "call-1", "Alice", "safe result"
                )))
                .build();

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(
                    new ChatClientRequest(new Prompt(List.of(response)), Map.of()),
                    session.handle()
            );
            assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("Tool control field rejected by privacy guardrail")
                    .hasMessageNotContaining("Alice");
            verify(chain, never()).nextCall(any());
        }
    }

    @Test
    void advisorRejectsPiiInModelVisibleToolDefinitionsBeforeModelCall() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ToolCallback callback = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("customerLookup")
                        .description("Looks up Alice")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String input) {
                return "ok";
            }
        };
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(factory.wrap(callback)))
                .build();

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(
                    new ChatClientRequest(new Prompt("hello", options), Map.of()),
                    session.handle()
            );

            assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("Tool definition rejected by privacy guardrail")
                    .hasMessageNotContaining("Alice");
            verify(chain, never()).nextCall(any());
        }
    }

    @Test
    void advisorRejectsNonblankMalformedJsonSchemasBeforeModelCall() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyToolCallbackFactory factory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll()
        );
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        ToolCallingChatOptions objectShapedToolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(factory.wrap(tool("customerLookup", "{not-json"))))
                .build();
        ToolCallingChatOptions plainToolOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(factory.wrap(tool("customerLookup", "not-json"))))
                .build();

        try (PrivacySession session = service.openSession()) {
            for (ChatClientRequest request : List.of(
                    new ChatClientRequest(new Prompt("hello", objectShapedToolOptions), Map.of()),
                    new ChatClientRequest(new Prompt("hello", plainToolOptions), Map.of()),
                    new ChatClientRequest(
                            new Prompt("hello"),
                            Map.of(
                                    ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey(),
                                    "{not-json"
                            )
                    ),
                    new ChatClientRequest(
                            new Prompt("hello"),
                            Map.of(
                                    ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey(),
                                    "not-json"
                            )
                    )
            )) {
                ChatClientRequest activeRequest = activeRequest(request, session.handle());

                assertThatThrownBy(() -> advisor.adviseCall(activeRequest, chain))
                        .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure -> {
                            assertThat(failure.code()).isEqualTo(PrivacyFailureCode.TRANSFORMATION_CONFLICT);
                            assertThat(failure.phase()).isEqualTo(PrivacyPhase.TOKENIZATION);
                            assertThat(failure).hasMessage("Structured JSON payload is invalid");
                        });
            }
            verify(chain, never()).nextCall(any());
        }
    }

    @Test
    void advisorRejectsLateStructuredOutputContextBeforeModelCall() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            for (Map.Entry<String, String> augmentation : Map.of(
                    ChatClientAttributes.OUTPUT_FORMAT.getKey(), "Return Alice",
                    ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey(),
                    "{\"description\":\"Alice\"}"
            ).entrySet()) {
                ChatClientRequest request = activeRequest(
                        new ChatClientRequest(
                                new Prompt("hello"),
                                Map.of(augmentation.getKey(), augmentation.getValue())
                        ),
                        session.handle()
                );

                assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                        .isInstanceOf(PrivacyGuardrailException.class)
                        .hasMessage("Terminal model augmentation rejected by privacy guardrail")
                        .hasMessageNotContaining("Alice");
            }
            verify(chain, never()).nextCall(any());
        }
    }

    @Test
    void callBoundaryPreservesTheModelProviderFailure() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        IllegalStateException raw = new IllegalStateException(
                "provider response contained Alice",
                new IllegalArgumentException("Alice cause")
        );
        raw.addSuppressed(new IllegalStateException("Alice suppressed"));
        when(chain.nextCall(any())).thenThrow(raw);

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(
                    new ChatClientRequest(new Prompt("hello"), Map.of()),
                    session.handle()
            );

            assertThatThrownBy(() -> advisor.adviseCall(request, chain)).isSameAs(raw);
        }
    }

    @Test
    void streamBoundaryPreservesFailureAfterAPartialFrame() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        IllegalStateException raw = new IllegalStateException("provider response contained Alice");
        when(chain.nextStream(any())).thenReturn(Flux.concat(
                Flux.just(TestPrivacyServices.response("partial")),
                Flux.error(raw)
        ));
        AtomicInteger emitted = new AtomicInteger();

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(
                    new ChatClientRequest(new Prompt("hello"), Map.of()),
                    session.handle()
            );

            assertThatThrownBy(() -> advisor.adviseStream(request, chain)
                    .doOnNext(ignored -> emitted.incrementAndGet())
                    .collectList()
                    .block())
                    .isSameAs(raw);
            assertThat(emitted).hasValue(1);
        }
    }

    @Test
    void modelBoundaryPreservesProviderGuardrailAndFatalFailures() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        PrivacyGuardrailException guardrail = new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                PrivacyPhase.TOKENIZATION,
                "safe"
        );

        try (PrivacySession session = service.openSession()) {
            ChatClientRequest request = activeRequest(
                    new ChatClientRequest(new Prompt("hello"), Map.of()),
                    session.handle()
            );
            when(chain.nextCall(any())).thenThrow(guardrail);
            assertThatThrownBy(() -> advisor.adviseCall(request, chain)).isSameAs(guardrail);

            LinkageError fatal = new LinkageError("fatal");
            doThrow(fatal).when(chain).nextCall(any());
            assertThatThrownBy(() -> advisor.adviseCall(request, chain)).isSameAs(fatal);
        }
    }

    @Test
    void streamBoundaryPreservesIncrementalFramesWhileApplyingPreAggregationGuard() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyModelBoundaryAdvisor advisor = new PrivacyModelBoundaryAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.concat(
                Flux.just(TestPrivacyServices.response("first")),
                Flux.never()
        ));

        try (PrivacySession session = service.openSession()) {
            List<ChatClientResponse> first = advisor.adviseStream(
                    toolRequest(service, session, "hello"),
                    chain
            ).take(1).collectList().block();

            assertThat(first).singleElement().satisfies(response -> assertThat(
                    response.chatResponse().getResult().getOutput().getText()
            ).isEqualTo("first"));
        }
    }

    private ChatClientRequest toolRequest(
            PrivacyService service,
            PrivacySession session,
            String text
    ) {
        ToolCallback callback = tool("customerLookup");
        ToolCallback wrapped = new PrivacyToolCallbackFactory(service, ToolDisclosurePolicy.denyAll())
                .wrap(callback);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(List.of(wrapped))
                .build();
        return activeRequest(
                new ChatClientRequest(new Prompt(List.of(new UserMessage(text)), options), Map.of()),
                session.handle()
        );
    }

    private ChatClientRequest activeRequest(
            ChatClientRequest request,
            PrivacyContextHandle handle
    ) {
        return PrivacyToolExecutionContextSupport.attachValidatedToolCallbackSnapshot(
                PrivacyRequestContextSupport.attachLifecycle(request, handle)
        );
    }

    private ToolCallback tool(String name) {
        return tool(name, "{}");
    }

    private ToolCallback tool(String name, String inputSchema) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("lookup")
                        .inputSchema(inputSchema)
                        .build();
            }

            @Override
            public String call(String input) {
                return "ok";
            }
        };
    }

}
