package io.github.ultramancode.springai.privacy.security;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.DefinitionResolvingModel;
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

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void factoryInspectionReceivesOnlyAuthorizedTools(
        boolean streaming,
        boolean useCustomToolAdvisorOrder
    ) {
        SpringSecurityToolBoundary toolAuthorizationBoundary = boundary((authentication, context) ->
            new AuthorizationDecision(context.toolDefinition().name().equals("customerLookup")));
        ToolAuthorizationChatClientFactory toolAuthorizationFactory = ToolAuthorizationChatClientFactory.builder()
            .toolAuthorizationBoundary(toolAuthorizationBoundary)
            .build();
        DefinitionResolvingModel chatModel = new DefinitionResolvingModel(ToolCallingManager.builder().build());
        AtomicReference<ChatClientRequest> inspectedRequest = new AtomicReference<>();
        ModelRequestBoundaryConfigurer inspectionConfigurer = (chatClientBuilder, boundarySpec) ->
            boundarySpec.inspection((request, ignoredStreaming) -> inspectedRequest.set(request));

        ChatClient.Builder chatClientBuilder;
        if (useCustomToolAdvisorOrder) {
            ToolCallingAdvisor.Builder<?> toolAdvisorBuilder = ToolCallingAdvisor.builder().advisorOrder(100);
            chatClientBuilder = toolAuthorizationFactory.builder(chatModel, toolAdvisorBuilder, inspectionConfigurer);
        } else {
            chatClientBuilder = toolAuthorizationFactory.builderWithBoundary(chatModel, inspectionConfigurer);
        }
        ChatClient chatClient = chatClientBuilder
            .defaultTools(
                tool("customerLookup", ignored -> { }),
                tool("adminDelete", ignored -> { })
            )
            .build();
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
        ChatClientRequest authorizedRequest = inspectedRequest.get();
        assertThat(authorizedRequest).isNotNull();
        ToolCallingChatOptions authorizedOptions = (ToolCallingChatOptions) authorizedRequest.prompt().getOptions();
        assertThat(authorizedOptions.getToolCallbacks())
            .extracting(callback -> callback.getToolDefinition().name())
            .containsExactly("customerLookup");
    }

    @Test
    void filtersDefinitionsAndExecutesAnAuthorizedToolWithoutPrivacyAdvisors() {
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
        AtomicReference<String> customerLookupInput = new AtomicReference<>();
        AtomicInteger adminDeleteCalls = new AtomicInteger();
        ToolCallback customerLookup = tool("customerLookup", customerLookupInput::set);
        ToolCallback adminDelete = tool(
            "adminDelete",
            ignored -> adminDeleteCalls.incrementAndGet()
        );
        ResolvingToolLoopModel chatModel = new ResolvingToolLoopModel(
            toolAuthorizationBoundary.toolCallingManager(),
            List.of("customerLookup")
        );
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
            .toolCallingManager(toolAuthorizationBoundary.toolCallingManager())
            .build();
        ChatClient chatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(toolAuthorizationBoundary.toolAuthorizationAdvisor(), toolCallingAdvisor)
            .defaultTools(customerLookup, adminDelete)
            .build();
        useAuthentication(authentication("alice"));

        assertThat(chatClient.prompt().user("Find Alice").call().content()).isEqualTo("done");
        assertThat(chatModel.exposedToolNames()).containsOnly("customerLookup");
        assertThat(customerLookupInput).hasValue("{\"name\":\"Alice\"}");
        assertThat(adminDeleteCalls).hasValue(0);
        assertThat(definitionAuthorizationChecks).hasValueGreaterThanOrEqualTo(1);
        assertThat(executionAuthorizationChecks).hasValue(2);
        assertThat(toolAuthorizationBoundary.activeSessionCount()).isZero();
    }

    @Test
    void rejectsToolExecutionWhenCallbackReauthorizationFails() {
        AtomicInteger executionAuthorizationChecks = new AtomicInteger();
        AuthorizationManager<ToolAuthorizationContext> authorizationManager = (authentication, context) -> {
            if (context.phase() == ToolAuthorizationPhase.DEFINITION) {
                return new AuthorizationDecision(true);
            }
            return new AuthorizationDecision(executionAuthorizationChecks.incrementAndGet() == 1);
        };
        SpringSecurityToolBoundary toolAuthorizationBoundary = boundary(authorizationManager);
        AtomicInteger customerLookupCalls = new AtomicInteger();
        ToolCallback customerLookup = tool(
            "customerLookup",
            ignored -> customerLookupCalls.incrementAndGet()
        );
        ResolvingToolLoopModel chatModel = new ResolvingToolLoopModel(
            toolAuthorizationBoundary.toolCallingManager(),
            List.of("customerLookup")
        );
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
            .toolCallingManager(toolAuthorizationBoundary.toolCallingManager())
            .build();
        ChatClient chatClient = ChatClient.builder(chatModel)
            .defaultAdvisors(toolAuthorizationBoundary.toolAuthorizationAdvisor(), toolCallingAdvisor)
            .defaultTools(customerLookup)
            .build();
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
            .isInstanceOf(AuthorizationDeniedException.class)
            .hasMessage("Tool execution was not authorized");
        assertThat(executionAuthorizationChecks).hasValue(2);
        assertThat(customerLookupCalls).hasValue(0);
        assertThat(toolAuthorizationBoundary.activeSessionCount()).isZero();
    }
}
