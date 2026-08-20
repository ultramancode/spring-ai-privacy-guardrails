package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivacyEnforcementObserverTest {

    @Test
    void eventContractExposesOnlyBoundaryAndOutcome() {
        assertThat(Arrays.stream(PrivacyEnforcementEvent.class.getRecordComponents())
                .map(RecordComponent::getName))
                .containsExactly("boundary", "outcome");
        assertThat(Arrays.stream(PrivacyEnforcementEvent.class.getRecordComponents())
                .map(RecordComponent::getType))
                .containsExactly(
                        PrivacyEnforcementBoundary.class,
                        PrivacyEnforcementOutcome.class
                );
    }

    @Test
    void supportedBoundariesReportOnlyHighLevelEnforcementOutcomes() {
        PrivacyService service = TestPrivacyServices.privacyService();
        List<PrivacyEnforcementEvent> events = new ArrayList<>();
        PrivacyEnforcementObserver observer = events::add;
        PrivacyModelBoundaryAdvisor modelBoundary = new PrivacyModelBoundaryAdvisor(
                service,
                null,
                observer,
                PrivacyModelBoundaryAdvisor.DEFAULT_ORDER
        );
        PrivacyToolCallbackFactory toolFactory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.byToolName(Map.of("lookup", Set.of("PERSON"))),
                observer
        );
        PrivacyOutputAdvisor outputBoundary = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                PrivacyResponseInspectionLimits.defaults(),
                observer
        );
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenReturn(TestPrivacyServices.response("safe"));

        try (PrivacySession session = service.openSession()) {
            modelBoundary.adviseCall(activeRequest("Alice", session.handle()), chain);

            String personToken = service.tokenize(session.handle(), "Alice");
            ToolCallback protectedTool = toolFactory.wrap(tool(input -> {
                assertThat(input).contains("Alice").doesNotContain("[[PII_");
                return "Bob";
            }));
            String protectedResult = protectedTool.call(
                    "{\"name\":\"" + personToken + "\"}",
                    toolContext(session.handle())
            );
            assertThat(protectedResult).doesNotContain("Bob");

            outputBoundary.protectAtApplicationBoundary(
                    session.handle(),
                    response("Alice")
            );
        }

        assertThat(events).containsExactly(
                new PrivacyEnforcementEvent(
                        PrivacyEnforcementBoundary.MODEL,
                        PrivacyEnforcementOutcome.PROTECTED
                ),
                new PrivacyEnforcementEvent(
                        PrivacyEnforcementBoundary.TOOL_INPUT,
                        PrivacyEnforcementOutcome.DISCLOSED
                ),
                new PrivacyEnforcementEvent(
                        PrivacyEnforcementBoundary.TOOL_RESULT,
                        PrivacyEnforcementOutcome.PROTECTED
                ),
                new PrivacyEnforcementEvent(
                        PrivacyEnforcementBoundary.APPLICATION_OUTPUT,
                        PrivacyEnforcementOutcome.PROTECTED
                )
        );
        assertThat(events.toString()).doesNotContain("Alice", "Bob", "PERSON", "[[PII_");
    }

    @Test
    void blockedApplicationOutputReportsBlockedBeforeTheSafeExceptionEscapes() {
        PrivacyService service = TestPrivacyServices.privacyService();
        List<PrivacyEnforcementEvent> events = new ArrayList<>();
        PrivacyOutputAdvisor outputBoundary = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.BLOCK,
                "blocked",
                PrivacyResponseInspectionLimits.defaults(),
                events::add
        );

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> outputBoundary.protectAtApplicationBoundary(
                    session.handle(),
                    response("Alice")
            ))
                    .isInstanceOf(PrivacyOutputBlockedException.class)
                    .hasMessage("blocked");
        }

        assertThat(events).containsExactly(new PrivacyEnforcementEvent(
                PrivacyEnforcementBoundary.APPLICATION_OUTPUT,
                PrivacyEnforcementOutcome.BLOCKED
        ));
    }

    @Test
    void allowedToolScopeWithoutAnOriginalRestorationReportsProtected() {
        PrivacyService service = TestPrivacyServices.privacyService();
        List<PrivacyEnforcementEvent> events = new ArrayList<>();
        PrivacyToolCallbackFactory toolFactory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.byToolName(Map.of("lookup", Set.of("PERSON"))),
                events::add
        );
        ToolCallback protectedTool = toolFactory.wrap(tool(input -> "safe result"));

        try (PrivacySession session = service.openSession()) {
            protectedTool.call(
                    " { \"query\" : \"safe\" } ",
                    toolContext(session.handle())
            );
        }

        assertThat(events).containsExactly(
                new PrivacyEnforcementEvent(
                        PrivacyEnforcementBoundary.TOOL_INPUT,
                        PrivacyEnforcementOutcome.PROTECTED
                ),
                new PrivacyEnforcementEvent(
                        PrivacyEnforcementBoundary.TOOL_RESULT,
                        PrivacyEnforcementOutcome.PROTECTED
                )
        );
    }

    @Test
    void disallowedProtectedToolInputReportsProtected() {
        PrivacyService service = TestPrivacyServices.privacyService();
        List<PrivacyEnforcementEvent> events = new ArrayList<>();
        PrivacyToolCallbackFactory toolFactory = new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.denyAll(),
                events::add
        );

        try (PrivacySession session = service.openSession()) {
            String personToken = service.tokenize(session.handle(), "Alice");
            ToolCallback protectedTool = toolFactory.wrap(tool(input -> {
                assertThat(input).contains(personToken).doesNotContain("Alice");
                return "safe result";
            }));

            protectedTool.call(
                    "{\"name\":\"" + personToken + "\"}",
                    toolContext(session.handle())
            );
        }

        assertThat(events).containsExactly(
                new PrivacyEnforcementEvent(
                        PrivacyEnforcementBoundary.TOOL_INPUT,
                        PrivacyEnforcementOutcome.PROTECTED
                ),
                new PrivacyEnforcementEvent(
                        PrivacyEnforcementBoundary.TOOL_RESULT,
                        PrivacyEnforcementOutcome.PROTECTED
                )
        );
    }

    @Test
    void returnDirectApplicationOutputReportsProtectedExactlyOnce() {
        PrivacyService service = TestPrivacyServices.privacyService();
        List<PrivacyEnforcementEvent> events = new ArrayList<>();
        PrivacyOutputAdvisor outputBoundary = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.REDACT,
                "blocked",
                PrivacyResponseInspectionLimits.defaults(),
                events::add
        );

        try (PrivacySession session = service.openSession()) {
            String personToken = service.tokenize(session.handle(), "Alice");
            Generation returnDirectGeneration = new Generation(
                    new AssistantMessage(personToken),
                    ChatGenerationMetadata.builder()
                            .finishReason(ToolExecutionResult.FINISH_REASON)
                            .build()
            );

            ChatClientResponse protectedResponse = outputBoundary.protectAtApplicationBoundary(
                    session.handle(),
                    new ChatClientResponse(
                            new ChatResponse(List.of(returnDirectGeneration)),
                            Map.of()
                    )
            );

            assertThat(protectedResponse.chatResponse().getResult().getOutput().getText())
                    .contains("[REDACTED_PERSON]")
                    .doesNotContain("Alice", "[[PII_");
        }

        assertThat(events).containsExactly(new PrivacyEnforcementEvent(
                PrivacyEnforcementBoundary.APPLICATION_OUTPUT,
                PrivacyEnforcementOutcome.PROTECTED
        ));
    }

    @Test
    void streamingApplicationOutputReportsOnceAfterTheLogicalResponseCompletes() {
        PrivacyService service = TestPrivacyServices.privacyService();
        List<PrivacyEnforcementEvent> events = new ArrayList<>();
        PrivacyOutputAdvisor outputBoundary = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                PrivacyResponseInspectionLimits.defaults(),
                events::add
        );

        try (PrivacySession session = service.openSession()) {
            outputBoundary.protectAtApplicationBoundary(
                    session.handle(),
                    Flux.just(response("Alice"))
            ).collectList().block();
        }

        assertThat(events).containsExactly(new PrivacyEnforcementEvent(
                PrivacyEnforcementBoundary.APPLICATION_OUTPUT,
                PrivacyEnforcementOutcome.PROTECTED
        ));
    }

    @Test
    void streamingApplicationOutputReportsAfterProtectionWhenReplayIsCancelled() {
        PrivacyService service = TestPrivacyServices.privacyService();
        List<PrivacyEnforcementEvent> events = new ArrayList<>();
        PrivacyOutputAdvisor outputBoundary = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                PrivacyResponseInspectionLimits.defaults(),
                events::add
        );

        try (PrivacySession session = service.openSession()) {
            ChatClientResponse firstResponse = outputBoundary.protectAtApplicationBoundary(
                    session.handle(),
                    Flux.just(response("safe-"), response("response"))
            ).next().block();
            assertThat(firstResponse).isNotNull();
        }

        assertThat(events).containsExactly(new PrivacyEnforcementEvent(
                PrivacyEnforcementBoundary.APPLICATION_OUTPUT,
                PrivacyEnforcementOutcome.PROTECTED
        ));
    }

    @Test
    void streamingApplicationOutputReportsBlockedOnlyForItsOwnPolicyDecision() {
        PrivacyService service = TestPrivacyServices.privacyService();
        List<PrivacyEnforcementEvent> events = new ArrayList<>();
        PrivacyOutputAdvisor outputBoundary = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.BLOCK,
                "blocked",
                PrivacyResponseInspectionLimits.defaults(),
                events::add
        );

        PrivacyOutputBlockedException upstreamFailure =
                new PrivacyOutputBlockedException("upstream blocked");
        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> outputBoundary.protectAtApplicationBoundary(
                    session.handle(),
                    Flux.<ChatClientResponse>error(upstreamFailure)
            ).collectList().block()).isSameAs(upstreamFailure);
        }
        assertThat(events).isEmpty();

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> outputBoundary.protectAtApplicationBoundary(
                    session.handle(),
                    Flux.just(response("Alice"))
            ).collectList().block())
                    .isInstanceOf(PrivacyOutputBlockedException.class)
                    .hasMessage("blocked");
        }
        assertThat(events).containsExactly(new PrivacyEnforcementEvent(
                PrivacyEnforcementBoundary.APPLICATION_OUTPUT,
                PrivacyEnforcementOutcome.BLOCKED
        ));
    }

    @Test
    void nonFatalObserverFailuresCannotChangePrivacyEnforcement() {
        PrivacyEnforcementNotifier notifier = new PrivacyEnforcementNotifier(event -> {
            throw new IllegalStateException("observer unavailable");
        });

        assertThatCode(() -> notifier.notify(
                PrivacyEnforcementBoundary.MODEL,
                PrivacyEnforcementOutcome.PROTECTED
        )).doesNotThrowAnyException();
    }

    private ChatClientRequest activeRequest(String text, PrivacyContextHandle handle) {
        ChatClientRequest request = new ChatClientRequest(new Prompt(text), Map.of());
        return PrivacyToolExecutionContextSupport.attachValidatedToolCallbackSnapshot(
                PrivacyRequestContextSupport.attachLifecycle(request, handle)
        );
    }

    private ToolContext toolContext(PrivacyContextHandle handle) {
        return new ToolContext(Map.of(PrivacyRequestContextSupport.CONTEXT_HANDLE, handle));
    }

    private ToolCallback tool(Callback callback) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("lookup")
                        .description("lookup")
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return callback.call(toolInput);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return callback.call(toolInput);
            }
        };
    }

    private ChatClientResponse response(String text) {
        return new ChatClientResponse(
                new ChatResponse(List.of(new Generation(new AssistantMessage(text)))),
                Map.of()
        );
    }

    @FunctionalInterface
    private interface Callback {
        String call(String input);
    }
}
