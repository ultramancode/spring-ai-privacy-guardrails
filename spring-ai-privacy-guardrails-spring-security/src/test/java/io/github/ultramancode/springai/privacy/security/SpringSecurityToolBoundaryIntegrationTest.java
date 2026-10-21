package io.github.ultramancode.springai.privacy.security;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.DefinitionResolvingModel;
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

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void factoryInspectionReceivesProtectedMessagesAndOnlyAuthorizedTools(
        boolean streaming,
        boolean useCustomToolAdvisorOrder
    ) {
        SpringSecurityToolBoundary toolAuthorizationBoundary = boundary((authentication, context) ->
            new AuthorizationDecision(context.toolDefinition().name().equals("customerLookup")));
        PrivacyService privacyService = privacyService();
        PrivacyToolCallbackFactory toolCallbackFactory = privacyFactory(privacyService, Set.of("customerLookup"));
        PrivacyChatClientConfigurer privacyConfigurer =
            new PrivacyChatClientConfigurer(privacyService, toolCallbackFactory);
        ToolAuthorizationChatClientFactory toolAuthorizationFactory = ToolAuthorizationChatClientFactory.builder()
            .toolAuthorizationBoundary(toolAuthorizationBoundary)
            .build();
        PrivacySecurityChatClientFactory privacySecurityFactory =
            new PrivacySecurityChatClientFactory(privacyConfigurer, toolAuthorizationFactory);
        DefinitionResolvingModel chatModel = new DefinitionResolvingModel(ToolCallingManager.builder().build());
        AtomicReference<ChatClientRequest> inspectedRequest = new AtomicReference<>();
        ModelRequestBoundaryConfigurer inspectionConfigurer = (chatClientBuilder, boundarySpec) ->
            boundarySpec.inspection((request, ignoredStreaming) -> inspectedRequest.set(request));

        ChatClient.Builder chatClientBuilder;
        if (useCustomToolAdvisorOrder) {
            ToolCallingAdvisor.Builder<?> toolAdvisorBuilder = ToolCallingAdvisor.builder().advisorOrder(100);
            chatClientBuilder = privacySecurityFactory.builder(chatModel, toolAdvisorBuilder, inspectionConfigurer);
        } else {
            chatClientBuilder = privacySecurityFactory.builderWithBoundary(chatModel, inspectionConfigurer);
        }
        ToolCallback customerLookup = toolCallbackFactory.wrap(tool("customerLookup", ignored -> { }));
        ToolCallback adminDelete = toolCallbackFactory.wrap(tool("adminDelete", ignored -> { }));
        ChatClient chatClient = chatClientBuilder.defaultTools(customerLookup, adminDelete).build();
        useAuthentication(authentication("alice"));

        ChatClient.ChatClientRequestSpec request = chatClient.prompt().user("Find Alice");
        String result;
        if (streaming) {
            result = request.stream()
                .content()
                .collectList()
                .map(parts -> String.join("", parts))
                .block(Duration.ofSeconds(5));
        } else {
            result = request.call().content();
        }

        assertThat(result).isEqualTo("done");
        ChatClientRequest protectedRequest = inspectedRequest.get();
        assertThat(protectedRequest).isNotNull();
        assertThat(protectedRequest.prompt().getContents())
            .doesNotContain("Alice")
            .containsPattern(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
        ToolCallingChatOptions authorizedOptions = (ToolCallingChatOptions) protectedRequest.prompt().getOptions();
        assertThat(authorizedOptions.getToolCallbacks())
            .extracting(callback -> callback.getToolDefinition().name())
            .containsExactly("customerLookup");
    }

    @Test
    void filtersDefinitionsAndReauthorizesImmediatelyBeforePiiDisclosure() {
        AtomicInteger definitionAuthorizationChecks = new AtomicInteger();
        AtomicInteger executionAuthorizationChecks = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> authorizationManager = (authentication, context) -> {
            if (context.phase() == ToolAuthorizationPhase.DEFINITION) {
                definitionAuthorizationChecks.incrementAndGet();
                return new AuthorizationDecision(context.toolDefinition().name().equals("customerLookup"));
            }
            executionAuthorizationChecks.incrementAndGet();
            return new AuthorizationDecision(true);
        };
        SpringSecurityToolBoundary toolAuthorizationBoundary = boundary(authorizationManager);
        PrivacyService privacyService = privacyService();
        PrivacyToolCallbackFactory toolCallbackFactory = privacyFactory(privacyService, Set.of("customerLookup"));
        AtomicReference<String> customerLookupInput = new AtomicReference<>();
        AtomicReference<Map<String, Object>> customerLookupToolContext = new AtomicReference<>();
        AtomicInteger adminDeleteCalls = new AtomicInteger();
        ToolCallback authorizedCustomerLookup = toolCallbackFactory.wrap(contextAwareTool(
            "customerLookup",
            (input, context) -> {
                assertThat(executionAuthorizationChecks).hasValue(2);
                customerLookupInput.set(input);
                customerLookupToolContext.set(context.getContext());
            }
        ));
        ToolCallback unauthorizedAdminDelete = toolCallbackFactory.wrap(tool(
            "adminDelete",
            input -> adminDeleteCalls.incrementAndGet()
        ));
        ResolvingToolLoopModel chatModel = new ResolvingToolLoopModel(
            toolAuthorizationBoundary.toolCallingManager(),
            List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(
            privacyService,
            toolCallbackFactory,
            toolAuthorizationBoundary,
            chatModel,
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
        assertThat(chatModel.exposedToolNames()).containsOnly("customerLookup");
        assertThat(customerLookupInput.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(customerLookupToolContext.get())
            .containsEntry("tenant", "acme")
            .doesNotContainKey(ToolAuthorizationSessionRegistry.TOOL_AUTHORIZATION_SESSION_HANDLE_KEY);
        assertThat(adminDeleteCalls).hasValue(0);
        assertThat(definitionAuthorizationChecks).hasValueGreaterThanOrEqualTo(1);
        assertThat(toolAuthorizationBoundary.activeSessionCount()).isZero();
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void rejectsWhenCallbackReauthorizationFailsBeforePiiDisclosure() {
        AtomicInteger executionAuthorizationChecks = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> authorizationManager = (authentication, context) -> {
            if (context.phase() == ToolAuthorizationPhase.DEFINITION) {
                return new AuthorizationDecision(true);
            }
            // Permit the manager precheck, then deny callback-level reauthorization.
            return new AuthorizationDecision(executionAuthorizationChecks.incrementAndGet() == 1);
        };
        SpringSecurityToolBoundary toolAuthorizationBoundary = boundary(authorizationManager);
        PrivacyService privacyService = privacyService();
        PrivacyToolCallbackFactory toolCallbackFactory = privacyFactory(privacyService, Set.of("customerLookup"));
        AtomicReference<String> disclosedInput = new AtomicReference<>();
        ToolCallback protectedTool = toolCallbackFactory.wrap(tool("customerLookup", disclosedInput::set));
        ResolvingToolLoopModel chatModel = new ResolvingToolLoopModel(
            toolAuthorizationBoundary.toolCallingManager(),
            List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(
            privacyService, toolCallbackFactory, toolAuthorizationBoundary, chatModel, protectedTool
        );
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
            .isInstanceOf(AuthorizationDeniedException.class)
            .hasMessage("Tool execution was not authorized");
        assertThat(executionAuthorizationChecks).hasValue(2);
        assertThat(disclosedInput).hasNullValue();
        assertThat(toolAuthorizationBoundary.activeSessionCount()).isZero();
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void rejectsAHiddenToolNameEvenWhenTheModelHallucinatesIt() {
        AtomicInteger businessToolCalls = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> customerLookupDefinitionPolicy =
            (authentication, context) -> new AuthorizationDecision(
                context.phase() == ToolAuthorizationPhase.EXECUTION
                    || context.toolDefinition().name().equals("customerLookup")
            );
        SpringSecurityToolBoundary toolAuthorizationBoundary = boundary(customerLookupDefinitionPolicy);
        PrivacyService privacyService = privacyService();
        PrivacyToolCallbackFactory toolCallbackFactory = privacyFactory(
            privacyService,
            Set.of("customerLookup", "adminDelete")
        );
        ToolCallback authorizedCustomerLookup = toolCallbackFactory.wrap(tool(
            "customerLookup",
            ignored -> businessToolCalls.incrementAndGet()
        ));
        ToolCallback hiddenAdminDelete = toolCallbackFactory.wrap(tool(
            "adminDelete",
            ignored -> businessToolCalls.incrementAndGet()
        ));
        ResolvingToolLoopModel chatModel = new ResolvingToolLoopModel(
            toolAuthorizationBoundary.toolCallingManager(),
            List.of("adminDelete")
        );
        ChatClient chatClient = securedClient(
            privacyService,
            toolCallbackFactory,
            toolAuthorizationBoundary,
            chatModel,
            authorizedCustomerLookup,
            hiddenAdminDelete
        );
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
            .isInstanceOf(AuthorizationDeniedException.class)
            .hasMessage("A model-requested tool was not exposed by the authorization boundary");
        assertThat(chatModel.exposedToolNames()).containsOnly("customerLookup");
        assertThat(businessToolCalls).hasValue(0);
        assertThat(toolAuthorizationBoundary.activeSessionCount()).isZero();
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void rejectsAnUndeclaredToolBeforeADelegateResolverFallbackCanRun() {
        ToolCallingManager resolverFallbackDelegate = mock(ToolCallingManager.class);
        AuthorizationManager<ToolAuthorizationContext> allowAllPolicy =
            (authentication, context) -> new AuthorizationDecision(true);
        SpringSecurityToolBoundary toolAuthorizationBoundary = SpringSecurityToolBoundary.builder(
            resolverFallbackDelegate,
            allowAllPolicy
        ).build();
        ToolCallback declaredCustomerLookup = tool("customerLookup", ignored -> { });
        ResolvingToolLoopModel chatModel = new ResolvingToolLoopModel(
            toolAuthorizationBoundary.toolCallingManager(),
            List.of("resolverOnlyTool")
        );
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
            .toolCallingManager(toolAuthorizationBoundary.toolCallingManager())
            .build();
        ChatClient chatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(toolAuthorizationBoundary.toolAuthorizationAdvisor(), toolCallingAdvisor)
            .defaultTools(declaredCustomerLookup)
            .build();
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
            .isInstanceOf(AuthorizationDeniedException.class)
            .hasMessage("A model-requested tool was not exposed by the authorization boundary");
        assertThat(chatModel.exposedToolNames()).containsOnly("customerLookup");
        verifyNoInteractions(resolverFallbackDelegate);
        assertThat(toolAuthorizationBoundary.activeSessionCount()).isZero();
    }

    @Test
    void preauthorizesTheWholeBatchBeforeAnyToolCanExecute() {
        AtomicInteger executedToolCalls = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> readCustomerExecutionPolicy =
            (authentication, context) -> new AuthorizationDecision(
                context.phase() == ToolAuthorizationPhase.DEFINITION
                    || context.toolDefinition().name().equals("readCustomer")
            );
        SpringSecurityToolBoundary toolAuthorizationBoundary = boundary(readCustomerExecutionPolicy);
        PrivacyService privacyService = privacyService();
        PrivacyToolCallbackFactory toolCallbackFactory = privacyFactory(
            privacyService,
            Set.of("readCustomer", "deleteCustomer")
        );
        ToolCallback readCustomer = toolCallbackFactory.wrap(tool(
            "readCustomer",
            ignored -> executedToolCalls.incrementAndGet()
        ));
        ToolCallback deleteCustomer = toolCallbackFactory.wrap(tool(
            "deleteCustomer",
            ignored -> executedToolCalls.incrementAndGet()
        ));
        ResolvingToolLoopModel chatModel = new ResolvingToolLoopModel(
            toolAuthorizationBoundary.toolCallingManager(),
            List.of("readCustomer", "deleteCustomer")
        );
        ChatClient chatClient = securedClient(
            privacyService,
            toolCallbackFactory,
            toolAuthorizationBoundary,
            chatModel,
            readCustomer,
            deleteCustomer
        );
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
            .isInstanceOf(AuthorizationDeniedException.class)
            .hasMessage("Tool execution was not authorized");
        assertThat(executedToolCalls).hasValue(0);
        assertThat(toolAuthorizationBoundary.activeSessionCount()).isZero();
        assertThat(privacyService.activeSessionCount()).isZero();
    }
}
