package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static io.github.ultramancode.springai.privacy.springai.TestPrivacyServices.privacyService;
import static io.github.ultramancode.springai.privacy.springai.TestPrivacyServices.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PrivacyInputAdvisorTest {

    @Test
    void adviseCallRejectsForgedContextWithoutLifecycleMarkerBeforeModelCall() {
        PrivacyService service = privacyService();
        PrivacyInputAdvisor advisor = new PrivacyInputAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        try (var session = service.openSession()) {
            ChatClientRequest request = new ChatClientRequest(
                    new Prompt("Alice hello"),
                    Map.of(PrivacyRequestContextSupport.CONTEXT_HANDLE, session.handle())
            );

            assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("PrivacyInputAdvisor requires PrivacyLifecycleAdvisor");
            assertThat(service.isSessionActive(session.handle())).isTrue();
        }
        verifyNoInteractions(chain);
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void defaultOrderRunsAfterSpringAiMemoryAndBeforeToolCalling() {
        PrivacyInputAdvisor advisor = new PrivacyInputAdvisor(privacyService());

        assertThat(advisor.getOrder()).isEqualTo(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER + 50);
        assertThat(new PrivacyInputAdvisor(TestPrivacyServices.privacyService(), 123).getOrder())
                .isEqualTo(123);
    }

    @Test
    void adviseCallTokenizesMessagesInsideLifecycleOwnedSession() {
        PrivacyService service = privacyService();
        PrivacyInputAdvisor advisor = new PrivacyInputAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(invocation -> {
            ChatClientRequest updated = invocation.getArgument(0);
            PrivacyContextHandle handle = PrivacyRequestContextSupport.findHandle(updated).orElseThrow();
            String text = updated.prompt().getUserMessage().getText();
            assertThat(text).matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern() + " hello");
            assertThat(service.detokenize(handle, text)).isEqualTo("Alice hello");
            return new ChatClientResponse(response("ok").chatResponse(), updated.context());
        });

        try (var session = service.openSession()) {
            ChatClientRequest request = PrivacyRequestContextSupport.attachLifecycle(
                    new ChatClientRequest(new Prompt("Alice hello"), Map.of()),
                    session.handle()
            );
            ChatClientResponse result = advisor.adviseCall(request, chain);

            assertThat(result.chatResponse().getResult().getOutput().getText()).isEqualTo("ok");
            assertThat(result.context())
                    .containsEntry(PrivacyRequestContextSupport.CONTEXT_HANDLE, session.handle());
            assertThat(service.isSessionActive(session.handle())).isTrue();
        }
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void adviseCallTokenizesAllModelBoundHistoryMessageKinds() {
        PrivacyService service = privacyService();
        PrivacyInputAdvisor advisor = new PrivacyInputAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(invocation -> {
            ChatClientRequest updated = invocation.getArgument(0);
            PrivacyContextHandle handle = PrivacyRequestContextSupport.findHandle(updated).orElseThrow();
            List<Message> messages = updated.prompt().getInstructions();

            assertThat(service.detokenize(handle, messages.get(0).getText())).isEqualTo("System for Alice");
            assertThat(service.detokenize(handle, messages.get(1).getText())).isEqualTo("Alice asks");
            AssistantMessage assistant = (AssistantMessage) messages.get(2);
            assertThat(service.detokenize(handle, assistant.getText())).isEqualTo("Alice replied");
            assertThat(service.detokenize(handle, assistant.getToolCalls().get(0).arguments()))
                    .isEqualTo("{\"name\":\"Alice\"}");
            ToolResponseMessage tool = (ToolResponseMessage) messages.get(3);
            assertThat(service.detokenize(handle, tool.getResponses().get(0).responseData()))
                    .isEqualTo("Alice result");
            return response("ok");
        });

        Prompt prompt = new Prompt(List.of(
                new SystemMessage("System for Alice"),
                new UserMessage("Alice asks"),
                AssistantMessage.builder()
                        .content("Alice replied")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "1", "function", "lookup", "{\"name\":\"Alice\"}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("1", "lookup", "Alice result")))
                        .build()
        ));

        try (var session = service.openSession()) {
            advisor.adviseCall(
                    PrivacyRequestContextSupport.attachLifecycle(
                            new ChatClientRequest(prompt, Map.of()),
                            session.handle()
                    ),
                    chain
            );
        }
    }

    @Test
    void adviseCallAddsOnlyOpaqueHandleToToolContext() {
        PrivacyService service = privacyService();
        PrivacyInputAdvisor advisor = new PrivacyInputAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(invocation -> {
            ChatClientRequest updated = invocation.getArgument(0);
            ToolCallingChatOptions options = (ToolCallingChatOptions) updated.prompt().getOptions();
            assertThat(options.getToolContext()).containsEntry("tenant", "acme");
            assertThat(options.getToolContext().get(PrivacyRequestContextSupport.CONTEXT_HANDLE))
                    .isInstanceOf(PrivacyContextHandle.class);
            assertThat(options.getToolContext().toString()).doesNotContain("Alice");
            return response("ok");
        });

        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext("tenant", "acme")
                .build();
        try (var session = service.openSession()) {
            advisor.adviseCall(
                    PrivacyRequestContextSupport.attachLifecycle(
                            new ChatClientRequest(
                                    new Prompt(List.of(new UserMessage("Alice hello")), options),
                                    Map.of()
                            ),
                            session.handle()
                    ),
                    chain
            );
        }
    }

    @Test
    void adviseCallRejectsMissingLifecycleContext() {
        PrivacyService service = privacyService();
        PrivacyInputAdvisor advisor = new PrivacyInputAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);

        assertThatThrownBy(() -> advisor.adviseCall(
                new ChatClientRequest(new Prompt("Alice hello"), Map.of()),
                chain
        ))
                .isInstanceOf(PrivacyGuardrailException.class)
                .hasMessageContaining("PrivacyLifecycleAdvisor");

        verifyNoInteractions(chain);
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void adviseStreamKeepsLifecycleSessionAcrossSignals() {
        PrivacyService service = privacyService();
        PrivacyInputAdvisor advisor = new PrivacyInputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenAnswer(invocation -> {
            ChatClientRequest updated = invocation.getArgument(0);
            PrivacyContextHandle handle = PrivacyRequestContextSupport.findHandle(updated).orElseThrow();
            assertThat(service.detokenize(handle, updated.prompt().getUserMessage().getText()))
                    .isEqualTo("Alice hello");
            return Flux.just(new ChatClientResponse(response("ok").chatResponse(), updated.context()));
        });

        try (var session = service.openSession()) {
            List<ChatClientResponse> result = advisor.adviseStream(
                    PrivacyRequestContextSupport.attachLifecycle(
                            new ChatClientRequest(new Prompt("Alice hello"), Map.of()),
                            session.handle()
                    ),
                    chain
            ).collectList().block();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).context())
                    .containsEntry(PrivacyRequestContextSupport.CONTEXT_HANDLE, session.handle());
            assertThat(service.isSessionActive(session.handle())).isTrue();
        }
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void adviseCallDoesNotOwnLifecycleCleanupWhenDownstreamThrows() {
        PrivacyService service = privacyService();
        PrivacyInputAdvisor advisor = new PrivacyInputAdvisor(service);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenThrow(new IllegalStateException("model failed"));

        try (var session = service.openSession()) {
            ChatClientRequest request = PrivacyRequestContextSupport.attachLifecycle(
                    new ChatClientRequest(new Prompt("Alice hello"), Map.of()),
                    session.handle()
            );
            assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(service.isSessionActive(session.handle())).isTrue();
        }
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void adviseStreamDoesNotOwnLifecycleCleanupWhenSubscriberCancels() {
        PrivacyService service = privacyService();
        PrivacyInputAdvisor advisor = new PrivacyInputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.never());

        try (var session = service.openSession()) {
            reactor.core.Disposable subscription = advisor.adviseStream(
                    PrivacyRequestContextSupport.attachLifecycle(
                            new ChatClientRequest(new Prompt("Alice hello"), Map.of()),
                            session.handle()
                    ),
                    chain
            ).subscribe();
            subscription.dispose();
            assertThat(service.isSessionActive(session.handle())).isTrue();
        }
        assertThat(service.activeSessionCount()).isZero();
    }

}
