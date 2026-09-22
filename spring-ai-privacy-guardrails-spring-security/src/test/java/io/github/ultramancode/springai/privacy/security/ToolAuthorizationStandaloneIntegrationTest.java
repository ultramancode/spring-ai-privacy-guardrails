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
    void factoryInspectionReceivesOnlyAuthorizedTools(boolean streaming, boolean customToolOrder) {
        SpringSecurityToolBoundary boundary = boundary((authentication, context) ->
                new AuthorizationDecision(context.toolDefinition().name().equals("customerLookup")));
        ToolAuthorizationChatClientFactory authorizationFactory = new ToolAuthorizationChatClientFactory(boundary);
        DefinitionResolvingModel model = new DefinitionResolvingModel(ToolCallingManager.builder().build());
        AtomicReference<ChatClientRequest> inspectedRequest = new AtomicReference<>();
        ModelRequestBoundaryConfigurer inspectionConfigurer =
                (clientBuilder, boundarySpec) -> boundarySpec.inspection(inspectedRequest::set);

        ChatClient.Builder clientBuilder;
        if (customToolOrder) {
            ToolCallingAdvisor.Builder<?> toolAdvisorBuilder = ToolCallingAdvisor.builder().advisorOrder(100);
            clientBuilder = authorizationFactory.builder(model, toolAdvisorBuilder, inspectionConfigurer);
        } else {
            clientBuilder = authorizationFactory.builderWithBoundary(model, inspectionConfigurer);
        }
        ChatClient client = clientBuilder.defaultTools(
                tool("customerLookup", ignored -> { }),
                tool("adminDelete", ignored -> { })).build();
        useAuthentication(authentication("alice"));

        ChatClient.ChatClientRequestSpec request = client.prompt().user("Find Alice");
        String result;
        if (streaming) {
            result = request.stream().content().collectList()
                    .map(parts -> String.join("", parts)).block(Duration.ofSeconds(5));
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
