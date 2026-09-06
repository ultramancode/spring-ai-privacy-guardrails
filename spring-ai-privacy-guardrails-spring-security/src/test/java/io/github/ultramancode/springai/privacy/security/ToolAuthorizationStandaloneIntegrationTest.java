package io.github.ultramancode.springai.privacy.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.ResolvingToolLoopModel;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.authentication;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.boundary;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.tool;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.useAuthentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolAuthorizationStandaloneIntegrationTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void filtersDefinitionsAndExecutesAnAuthorizedToolWithoutPrivacyAdvisors() {
        AtomicInteger definitionChecks = new AtomicInteger();
        AtomicInteger executionChecks = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) -> {
            if (context.phase() == ToolAuthorizationPhase.DEFINITION) {
                definitionChecks.incrementAndGet();
                return new AuthorizationDecision(
                        context.toolDefinition().name().equals("customerLookup")
                );
            }
            executionChecks.incrementAndGet();
            return new AuthorizationDecision(true);
        };
        SpringSecurityToolBoundary boundary = boundary(policy);
        AtomicReference<String> customerLookupInput = new AtomicReference<>();
        AtomicInteger adminDeleteCalls = new AtomicInteger();
        ToolCallback customerLookup = tool("customerLookup", customerLookupInput::set);
        ToolCallback adminDelete = tool(
                "adminDelete",
                ignored -> adminDeleteCalls.incrementAndGet()
        );
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(boundary.toolCallingManager())
                .build();
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(boundary.toolAuthorizationAdvisor(), toolCallingAdvisor)
                .defaultTools(customerLookup, adminDelete)
                .build();
        useAuthentication(authentication("alice"));

        assertThat(chatClient.prompt().user("Find Alice").call().content()).isEqualTo("done");
        assertThat(model.exposedToolNames()).containsOnly("customerLookup");
        assertThat(customerLookupInput).hasValue("{\"name\":\"Alice\"}");
        assertThat(adminDeleteCalls).hasValue(0);
        assertThat(definitionChecks).hasValueGreaterThanOrEqualTo(1);
        assertThat(executionChecks).hasValue(2);
        assertThat(boundary.activeSessionCount()).isZero();
    }

    @Test
    void rejectsCallbackReauthorizationBeforeExecutingARawTool() {
        AtomicInteger executionChecks = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) -> {
            if (context.phase() == ToolAuthorizationPhase.DEFINITION) {
                return new AuthorizationDecision(true);
            }
            return new AuthorizationDecision(executionChecks.incrementAndGet() == 1);
        };
        SpringSecurityToolBoundary boundary = boundary(policy);
        AtomicInteger toolCalls = new AtomicInteger();
        ToolCallback customerLookup = tool(
                "customerLookup",
                ignored -> toolCalls.incrementAndGet()
        );
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(boundary.toolCallingManager())
                .build();
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(boundary.toolAuthorizationAdvisor(), toolCallingAdvisor)
                .defaultTools(customerLookup)
                .build();
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("Tool execution was not authorized");
        assertThat(executionChecks).hasValue(2);
        assertThat(toolCalls).hasValue(0);
        assertThat(boundary.activeSessionCount()).isZero();
    }
}
