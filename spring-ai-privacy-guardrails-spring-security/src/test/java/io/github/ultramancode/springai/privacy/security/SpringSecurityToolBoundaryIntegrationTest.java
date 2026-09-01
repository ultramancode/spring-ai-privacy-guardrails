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
        AtomicInteger definitionAuthorizationChecks = new AtomicInteger();
        AtomicInteger executionAuthorizationChecks = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) -> {
            if (context.phase() == ToolAuthorizationPhase.DEFINITION) {
                definitionAuthorizationChecks.incrementAndGet();
                return new AuthorizationDecision(
                        context.toolDefinition().name().equals("customerLookup")
                );
            }
            executionAuthorizationChecks.incrementAndGet();
            return new AuthorizationDecision(true);
        };
        SpringSecurityToolBoundary boundary = boundary(policy);
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        AtomicReference<String> customerLookupInput = new AtomicReference<>();
        AtomicReference<Map<String, Object>> customerLookupToolContext = new AtomicReference<>();
        AtomicInteger adminDeleteCalls = new AtomicInteger();
        ToolCallback authorizedCustomerLookup = factory.wrap(contextAwareTool(
                "customerLookup",
                (input, context) -> {
                    assertThat(executionAuthorizationChecks).hasValue(2);
                    customerLookupInput.set(input);
                    customerLookupToolContext.set(context.getContext());
                }
        ));
        ToolCallback unauthorizedAdminDelete = factory.wrap(tool(
                "adminDelete",
                input -> adminDeleteCalls.incrementAndGet()
        ));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(
                service,
                factory,
                boundary,
                model,
                authorizedCustomerLookup,
                unauthorizedAdminDelete
        );
        useAuthentication(authentication("alice"));

        String result = chatClient.prompt()
                .user("Find Alice")
                .toolContext(Map.of("tenant", "acme"))
                .call()
                .content();

        assertThat(result).isEqualTo("done");
        assertThat(model.exposedToolNames()).containsOnly("customerLookup");
        assertThat(customerLookupInput.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(customerLookupToolContext.get())
                .containsEntry("tenant", "acme")
                .doesNotContainKey(SecurityToolSessionRegistry.TOOL_CONTEXT_HANDLE);
        assertThat(adminDeleteCalls).hasValue(0);
        assertThat(definitionAuthorizationChecks).hasValueGreaterThanOrEqualTo(1);
        assertThat(executionAuthorizationChecks).hasValue(2);
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void rejectsWhenCallbackReauthorizationFailsBeforePiiDisclosure() {
        AtomicInteger executionAuthorizationChecks = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) -> {
            if (context.phase() == ToolAuthorizationPhase.DEFINITION) {
                return new AuthorizationDecision(true);
            }
            // Permit the manager precheck, then deny callback-level reauthorization.
            return new AuthorizationDecision(
                    executionAuthorizationChecks.incrementAndGet() == 1
            );
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
        assertThat(executionAuthorizationChecks).hasValue(2);
        assertThat(disclosedInput).hasNullValue();
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void rejectsAHiddenToolNameEvenWhenTheModelHallucinatesIt() {
        AtomicInteger businessToolCalls = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> customerLookupDefinitionPolicy =
                (authentication, context) -> new AuthorizationDecision(
                        context.phase() == ToolAuthorizationPhase.EXECUTION
                                || context.toolDefinition().name().equals("customerLookup")
                );
        SpringSecurityToolBoundary boundary = boundary(customerLookupDefinitionPolicy);
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(
                service,
                Set.of("customerLookup", "adminDelete")
        );
        ToolCallback authorizedCustomerLookup = factory.wrap(tool(
                "customerLookup",
                ignored -> businessToolCalls.incrementAndGet()
        ));
        ToolCallback hiddenAdminDelete = factory.wrap(tool(
                "adminDelete",
                ignored -> businessToolCalls.incrementAndGet()
        ));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("adminDelete")
        );
        ChatClient chatClient = securedClient(
                service,
                factory,
                boundary,
                model,
                authorizedCustomerLookup,
                hiddenAdminDelete
        );
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("A model-requested tool was not exposed by the authorization boundary");
        assertThat(model.exposedToolNames()).containsOnly("customerLookup");
        assertThat(businessToolCalls).hasValue(0);
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void rejectsAnUndeclaredToolBeforeADelegateResolverFallbackCanRun() {
        ToolCallingManager resolverFallbackDelegate = mock(ToolCallingManager.class);
        AuthorizationManager<ToolAuthorizationContext> allowAllPolicy =
                (authentication, context) -> new AuthorizationDecision(true);
        SpringSecurityToolBoundary boundary = SpringSecurityToolBoundary.builder(
                resolverFallbackDelegate,
                allowAllPolicy
        ).build();
        ToolCallback declaredCustomerLookup = tool("customerLookup", ignored -> {
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
                .defaultTools(declaredCustomerLookup)
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
        AtomicInteger executedToolCalls = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> readCustomerExecutionPolicy =
                (authentication, context) -> new AuthorizationDecision(
                        context.phase() == ToolAuthorizationPhase.DEFINITION
                                || context.toolDefinition().name().equals("readCustomer")
                );
        SpringSecurityToolBoundary boundary = boundary(readCustomerExecutionPolicy);
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(
                service,
                Set.of("readCustomer", "deleteCustomer")
        );
        ToolCallback readCustomer = factory.wrap(tool(
                "readCustomer",
                ignored -> executedToolCalls.incrementAndGet()
        ));
        ToolCallback deleteCustomer = factory.wrap(tool(
                "deleteCustomer",
                ignored -> executedToolCalls.incrementAndGet()
        ));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("readCustomer", "deleteCustomer")
        );
        ChatClient chatClient = securedClient(
                service,
                factory,
                boundary,
                model,
                readCustomer,
                deleteCustomer
        );
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("Tool execution was not authorized");
        assertThat(executedToolCalls).hasValue(0);
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }
}
