package io.github.ultramancode.springai.privacy.security;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.ResolvingToolLoopModel;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.authentication;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.boundary;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.contextAwareTool;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.privacyFactory;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.privacyService;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.securedClient;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.tool;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.useAuthentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SpringSecurityToolBoundaryIntegrationTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void filtersDefinitionsAndReauthorizesImmediatelyBeforePiiDisclosure() {
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
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        AtomicReference<String> allowedInput = new AtomicReference<>();
        AtomicReference<Map<String, Object>> applicationContext = new AtomicReference<>();
        AtomicInteger deniedCalls = new AtomicInteger();
        ToolCallback allowed = factory.wrap(contextAwareTool("customerLookup", (input, context) -> {
            assertThat(executionChecks).hasValue(2);
            allowedInput.set(input);
            applicationContext.set(context.getContext());
        }));
        ToolCallback denied = factory.wrap(tool("adminDelete", input -> deniedCalls.incrementAndGet()));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, allowed, denied);
        useAuthentication(authentication("alice"));

        String result = chatClient.prompt().user("Find Alice").call().content();

        assertThat(result).isEqualTo("done");
        assertThat(model.exposedToolNames()).containsOnly("customerLookup");
        assertThat(allowedInput.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(applicationContext.get())
                .doesNotContainKey(SecurityToolSessionRegistry.TOOL_CONTEXT_HANDLE);
        assertThat(deniedCalls).hasValue(0);
        assertThat(definitionChecks).hasValueGreaterThanOrEqualTo(1);
        assertThat(executionChecks).hasValue(2);
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void rejectsWhenCallbackReauthorizationFailsBeforePiiDisclosure() {
        AtomicInteger executionChecks = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) -> {
            if (context.phase() == ToolAuthorizationPhase.DEFINITION) {
                return new AuthorizationDecision(true);
            }
            return new AuthorizationDecision(executionChecks.incrementAndGet() == 1);
        };
        SpringSecurityToolBoundary boundary = boundary(policy);
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        AtomicReference<String> disclosedInput = new AtomicReference<>();
        ToolCallback protectedTool = factory.wrap(tool("customerLookup", disclosedInput::set));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, protectedTool);
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("Tool execution was not authorized");
        assertThat(executionChecks).hasValue(2);
        assertThat(disclosedInput).hasNullValue();
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void rejectsAHiddenToolNameEvenWhenTheModelHallucinatesIt() {
        AtomicInteger toolCalls = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) ->
                new AuthorizationDecision(context.phase() == ToolAuthorizationPhase.EXECUTION
                        || context.toolDefinition().name().equals("customerLookup"));
        SpringSecurityToolBoundary boundary = boundary(policy);
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(
                service,
                Set.of("customerLookup", "adminDelete")
        );
        ToolCallback allowed = factory.wrap(tool("customerLookup", ignored -> toolCalls.incrementAndGet()));
        ToolCallback hidden = factory.wrap(tool("adminDelete", ignored -> toolCalls.incrementAndGet()));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("adminDelete")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, allowed, hidden);
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("A model-requested tool was not exposed by the authorization boundary");
        assertThat(model.exposedToolNames()).containsOnly("customerLookup");
        assertThat(toolCalls).hasValue(0);
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void rejectsAnUndeclaredToolBeforeADelegateResolverFallbackCanRun() {
        ToolCallingManager resolverFallbackDelegate = mock(ToolCallingManager.class);
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) ->
                new AuthorizationDecision(true);
        SpringSecurityToolBoundary boundary = SpringSecurityToolBoundary.builder(
                resolverFallbackDelegate,
                policy
        ).build();
        ToolCallback declared = tool("customerLookup", ignored -> {
        });
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("resolverOnlyTool")
        );
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(boundary.toolCallingManager())
                .build();
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(boundary.advisor(), toolCallingAdvisor)
                .defaultTools(declared)
                .build();
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("A model-requested tool was not exposed by the authorization boundary");
        assertThat(model.exposedToolNames()).containsOnly("customerLookup");
        verifyNoInteractions(resolverFallbackDelegate);
        assertThat(boundary.activeSessionCount()).isZero();
    }

    @Test
    void preauthorizesTheWholeBatchBeforeAnyToolCanExecute() {
        AtomicInteger executed = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) ->
                new AuthorizationDecision(context.phase() == ToolAuthorizationPhase.DEFINITION
                        || context.toolDefinition().name().equals("readCustomer"));
        SpringSecurityToolBoundary boundary = boundary(policy);
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(
                service,
                Set.of("readCustomer", "deleteCustomer")
        );
        ToolCallback read = factory.wrap(tool("readCustomer", ignored -> executed.incrementAndGet()));
        ToolCallback delete = factory.wrap(tool("deleteCustomer", ignored -> executed.incrementAndGet()));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("readCustomer", "deleteCustomer")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, read, delete);
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("Tool execution was not authorized");
        assertThat(executed).hasValue(0);
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }
}
