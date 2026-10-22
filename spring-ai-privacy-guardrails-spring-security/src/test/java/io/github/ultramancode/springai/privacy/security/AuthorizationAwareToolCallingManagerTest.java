package io.github.ultramancode.springai.privacy.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationManager;

import java.util.List;
import java.util.Map;

import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.authentication;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.tool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AuthorizationAwareToolCallingManagerTest {

    private final ToolAuthorizationSessionRegistry sessionRegistry = new ToolAuthorizationSessionRegistry();
    private final ToolCallingManager delegate = mock(ToolCallingManager.class);

    @Test
    void rejectsExecutionWithoutToolCallingOptions() {
        ToolCallingManager toolCallingManager = new AuthorizationAwareToolCallingManager(
                (authentication, context) -> new AuthorizationDecision(true), sessionRegistry, delegate);

        assertThatThrownBy(() -> toolCallingManager.executeToolCalls(
                new Prompt("Find customer"), new ChatResponse(List.of())))
                .isInstanceOfSatisfying(ToolAuthorizationDeniedException.class,
                        denial -> {
                            assertThat(denial.code())
                                    .isEqualTo(ToolAuthorizationDenialCode.INVALID_TOOL_CALL_OPTIONS);
                            assertThat(denial.phase()).contains(ToolAuthorizationPhase.EXECUTION);
                        });
        verifyNoInteractions(delegate);
    }

    @ParameterizedTest
    @NullSource
    // A session handle must be a ToolAuthorizationSessionHandle, not a string.
    @ValueSource(strings = "invalid-session-handle")
    void rejectsMissingOrInvalidSessionHandles(String invalidSessionHandle) {
        ToolCallingManager toolCallingManager = new AuthorizationAwareToolCallingManager(
                (authentication, context) -> new AuthorizationDecision(true), sessionRegistry, delegate);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolCallbacks(tool("customerLookup", ignored -> { }))
                .toolContext(invalidSessionHandle == null ? Map.of() : Map.of(
                        ToolAuthorizationSessionRegistry.TOOL_AUTHORIZATION_SESSION_HANDLE_KEY, invalidSessionHandle))
                .build();

        assertThatThrownBy(() -> toolCallingManager.resolveToolDefinitions(options))
                .isInstanceOfSatisfying(ToolAuthorizationDeniedException.class,
                        denial -> {
                            assertThat(denial.code())
                                    .isEqualTo(ToolAuthorizationDenialCode.INVALID_AUTHORIZATION_SESSION);
                            assertThat(denial.phase()).contains(ToolAuthorizationPhase.DEFINITION);
                        });
        assertThatThrownBy(() -> toolCallingManager.executeToolCalls(
                new Prompt("Find customer", options), new ChatResponse(List.of())))
                .isInstanceOfSatisfying(ToolAuthorizationDeniedException.class,
                        denial -> {
                            assertThat(denial.code())
                                    .isEqualTo(ToolAuthorizationDenialCode.INVALID_AUTHORIZATION_SESSION);
                            assertThat(denial.phase()).contains(ToolAuthorizationPhase.EXECUTION);
                        });
        verifyNoInteractions(delegate);
    }

    @Test
    void rejectsCallbackReplacementDuringExecution() {
        ToolCallingManager toolCallingManager = new AuthorizationAwareToolCallingManager(
                (authentication, context) -> new AuthorizationDecision(true), sessionRegistry, delegate);
        ToolCallback originalCallback = tool("customerLookup", ignored -> { });
        ToolCallback replacementCallback = tool("customerLookup", ignored -> { });
        try (ToolAuthorizationSession session = sessionRegistry.openSession(
                authentication("alice"), List.of(originalCallback))) {
            ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                    .toolCallbacks(originalCallback)
                    .toolContext(Map.of(
                            ToolAuthorizationSessionRegistry.TOOL_AUTHORIZATION_SESSION_HANDLE_KEY, session.handle()))
                    .build();
            assertThat(toolCallingManager.resolveToolDefinitions(options))
                    .containsExactly(originalCallback.getToolDefinition());
            ToolCallingChatOptions replacementOptions = options.mutate().toolCallbacks(replacementCallback).build();

            assertThatThrownBy(() -> toolCallingManager.executeToolCalls(
                    new Prompt("Find customer", replacementOptions), new ChatResponse(List.of())))
                    .isInstanceOfSatisfying(ToolAuthorizationDeniedException.class, denial -> {
                        assertThat(denial.code()).isEqualTo(ToolAuthorizationDenialCode.CALLBACK_CHANGED);
                        assertThat(denial.phase()).contains(ToolAuthorizationPhase.EXECUTION);
                    });
        }
        verifyNoInteractions(delegate);
    }

    @Test
    void omitsDefinitionsWhenPolicyReturnsNoDecision() {
        ToolCallback customerLookup = tool("customerLookup", ignored -> { });
        ToolCallingManager toolCallingManager = new AuthorizationAwareToolCallingManager(
                (authentication, context) -> null, sessionRegistry, delegate);
        try (ToolAuthorizationSession session = sessionRegistry.openSession(
                authentication("alice"), List.of(customerLookup))) {
            ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                    .toolCallbacks(customerLookup)
                    .toolContext(Map.of(
                            ToolAuthorizationSessionRegistry.TOOL_AUTHORIZATION_SESSION_HANDLE_KEY, session.handle()))
                    .build();

            assertThat(toolCallingManager.resolveToolDefinitions(options)).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(ToolAuthorizationPhase.class)
    void propagatesPolicyExceptions(ToolAuthorizationPhase failingPhase) {
        AuthorizationDeniedException policyFailure = new AuthorizationDeniedException("Application policy denied access");
        AuthorizationManager<ToolAuthorizationContext> authorizationManager = (authentication, context) -> {
            if (context.phase() == failingPhase) {
                throw policyFailure;
            }
            return new AuthorizationDecision(true);
        };
        ToolCallingManager toolCallingManager = new AuthorizationAwareToolCallingManager(
                authorizationManager, sessionRegistry, delegate);
        ToolCallback customerLookup = tool("customerLookup", ignored -> { });
        try (ToolAuthorizationSession session = sessionRegistry.openSession(
                authentication("alice"), List.of(customerLookup))) {
            ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                    .toolCallbacks(customerLookup)
                    .toolContext(Map.of(
                            ToolAuthorizationSessionRegistry.TOOL_AUTHORIZATION_SESSION_HANDLE_KEY, session.handle()))
                    .build();
            ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-lookup", "function", "customerLookup", "{}")))
                    .build())));

            assertThatThrownBy(() -> {
                toolCallingManager.resolveToolDefinitions(options);
                toolCallingManager.executeToolCalls(new Prompt("Find customer", options), toolCallResponse);
            }).isSameAs(policyFailure);
        }
        verifyNoInteractions(delegate);
    }
}
