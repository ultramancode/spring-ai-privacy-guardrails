package io.github.ultramancode.springai.privacy.security;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.springai.PrivacyInputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyLifecycleAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyModelBoundaryAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallValidationAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolContextAdvisor;
import io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.DefinitionResolvingModel;
import io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.ResolvingToolLoopModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.authentication;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.boundary;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.lateToolInjectionAdvisor;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.privacyFactory;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.privacyService;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.tool;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.useAuthentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringSecurityToolMutationIntegrationTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void failsClosedWhenAnAdvisorAddsAToolAfterTheAuthorizationBoundary() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(
                service,
                Set.of("customerLookup", "lateTool")
        );
        ToolCallback declared = factory.wrap(tool("customerLookup", ignored -> {
        }));
        ToolCallback injected = factory.wrap(tool("lateTool", ignored -> {
        }));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(boundary.toolCallingManager())
                .build();
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        boundary.advisor(),
                        lateToolInjectionAdvisor(injected),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service, factory),
                        toolCallingAdvisor,
                        new PrivacyToolCallValidationAdvisor(
                                service,
                                toolCallingAdvisor.getOrder() + 1
                        ),
                        new PrivacyModelBoundaryAdvisor(service, factory)
                )
                .defaultTools(declared)
                .build();
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("A tool callback was added after authorization");
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void allowsCallbackRemovalAfterTheAuthorizationBoundary() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        ToolCallback retained = tool("customerLookup", ignored -> {
        });
        ToolCallback removed = tool("adminDelete", ignored -> {
        });
        DefinitionResolvingModel model = new DefinitionResolvingModel(
                boundary.toolCallingManager()
        );
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        boundary.advisor(),
                        callbackMutationAdvisor(
                                "RemoveCallbackAdvisor",
                                callbacks -> callbacks.stream()
                                        .filter(callback -> callback != removed)
                                        .toList()
                        )
                )
                .defaultTools(retained, removed)
                .build();
        useAuthentication(authentication("alice"));

        assertThat(chatClient.prompt().user("Find customer").call().content())
                .isEqualTo("done");
        assertThat(model.exposedToolNames()).containsExactly("customerLookup");
        assertThat(boundary.activeSessionCount()).isZero();
    }

    @Test
    void allowsCallbackReorderingAfterTheAuthorizationBoundary() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        ToolCallback first = tool("customerLookup", ignored -> {
        });
        ToolCallback second = tool("adminDelete", ignored -> {
        });
        DefinitionResolvingModel model = new DefinitionResolvingModel(
                boundary.toolCallingManager()
        );
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        boundary.advisor(),
                        callbackMutationAdvisor(
                                "ReorderCallbacksAdvisor",
                                callbacks -> List.of(callbacks.get(1), callbacks.get(0))
                        )
                )
                .defaultTools(first, second)
                .build();
        useAuthentication(authentication("alice"));

        assertThat(chatClient.prompt().user("Find customer").call().content())
                .isEqualTo("done");
        assertThat(model.exposedToolNames())
                .containsExactly("adminDelete", "customerLookup");
        assertThat(boundary.activeSessionCount()).isZero();
    }

    @Test
    void failsClosedWhenAnAdvisorReplacesACallbackAfterAuthorization() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        ToolCallback declared = tool("customerLookup", ignored -> {
        });
        ToolCallback replacement = tool("customerLookup", ignored -> {
        });
        DefinitionResolvingModel model = new DefinitionResolvingModel(
                boundary.toolCallingManager()
        );
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        boundary.advisor(),
                        callbackMutationAdvisor(
                                "ReplaceCallbackAdvisor",
                                ignored -> List.of(replacement)
                        )
                )
                .defaultTools(declared)
                .build();
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find customer").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("A tool callback was replaced after authorization");
        assertThat(boundary.activeSessionCount()).isZero();
    }

    private static CallAdvisor callbackMutationAdvisor(
            String name,
            UnaryOperator<List<ToolCallback>> mutation
    ) {
        return new CallAdvisor() {
            @Override
            public ChatClientResponse adviseCall(
                    ChatClientRequest request,
                    CallAdvisorChain chain
            ) {
                ToolCallingChatOptions options =
                        (ToolCallingChatOptions) request.prompt().getOptions();
                List<ToolCallback> callbacks = mutation.apply(
                        List.copyOf(options.getToolCallbacks())
                );
                ToolCallingChatOptions mutated = options.mutate()
                        .toolCallbacks(callbacks)
                        .build();
                Prompt prompt = new Prompt(request.prompt().getInstructions(), mutated);
                return chain.nextCall(request.mutate().prompt(prompt).build());
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public int getOrder() {
                return SpringSecurityContextAdvisor.DEFAULT_ORDER + 1;
            }
        };
    }
}
