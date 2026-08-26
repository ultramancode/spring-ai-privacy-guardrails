package io.github.ultramancode.springai.privacy.security;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.springai.PrivacyInputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyLifecycleAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyModelBoundaryAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallValidationAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolContextAdvisor;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.toolsearch.ToolIndex;
import org.springframework.ai.tool.toolsearch.ToolReference;
import org.springframework.ai.tool.toolsearch.ToolSearchRequest;
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SpringSecurityToolBoundaryIntegrationTest {

    private static final Pattern PERSON_TOKEN =
            OpaquePiiTokenFormat.patternForEntityType("PERSON");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void filtersDefinitionsAndReauthorizesImmediatelyBeforePiiDisclosure() {
        AtomicInteger definitionChecks = new AtomicInteger();
        AtomicInteger executionChecks = new AtomicInteger();
        AtomicBoolean executionAuthorized = new AtomicBoolean();
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) -> {
            if (context.phase() == ToolAuthorizationPhase.DEFINITION) {
                definitionChecks.incrementAndGet();
                return new AuthorizationDecision(
                        context.toolDefinition().name().equals("customerLookup")
                );
            }
            executionChecks.incrementAndGet();
            executionAuthorized.set(true);
            return new AuthorizationDecision(true);
        };
        SpringSecurityToolBoundary boundary = boundary(policy);
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        AtomicReference<String> allowedInput = new AtomicReference<>();
        AtomicReference<Map<String, Object>> applicationContext = new AtomicReference<>();
        AtomicInteger deniedCalls = new AtomicInteger();
        ToolCallback allowed = factory.wrap(contextAwareTool("customerLookup", (input, context) -> {
            assertThat(executionAuthorized).isTrue();
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

    @Test
    void carriesReactiveSecurityContextAcrossStreamingToolExecution() {
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) ->
                new AuthorizationDecision(authentication.get().getName().equals("reactive-user"));
        SpringSecurityToolBoundary boundary = boundary(policy);
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        AtomicReference<String> input = new AtomicReference<>();
        ToolCallback tool = factory.wrap(tool("customerLookup", input::set));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, tool);
        SecurityContextHolder.clearContext();

        String result = chatClient.prompt()
                .user("Find Alice")
                .stream()
                .content()
                .collectList()
                .map(parts -> String.join("", parts))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        authentication("reactive-user")
                ))
                .block();

        assertThat(result).isEqualTo("done");
        assertThat(input.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void prefersReactiveSecurityContextOverThreadLocalContextForStreaming() {
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) ->
                new AuthorizationDecision(authentication.get().getName().equals("reactive-user"));
        SpringSecurityToolBoundary boundary = boundary(policy);
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        AtomicReference<String> input = new AtomicReference<>();
        ToolCallback tool = factory.wrap(tool("customerLookup", input::set));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, tool);
        useAuthentication(authentication("thread-user"));

        String result = chatClient.prompt()
                .user("Find Alice")
                .stream()
                .content()
                .collectList()
                .map(parts -> String.join("", parts))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        authentication("reactive-user")
                ))
                .block();

        assertThat(result).isEqualTo("done");
        assertThat(input.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void releasesTheAuthorizationSessionWhenAStreamIsCancelled() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        ChatClient chatClient = ChatClient.builder(new NeverStreamingModel())
                .defaultAdvisors(boundary.advisor())
                .defaultTools(tool("customerLookup", ignored -> {
                }))
                .build();
        useAuthentication(authentication("stream-user"));

        Disposable subscription = chatClient.prompt()
                .user("Keep streaming")
                .stream()
                .content()
                .subscribe();

        assertThat(boundary.activeSessionCount()).isOne();
        subscription.dispose();
        assertThat(boundary.activeSessionCount()).isZero();
    }

    @Test
    void failsClosedWhenNoSecurityContextIsAvailable() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        ToolCallback tool = factory.wrap(tool("customerLookup", ignored -> {
        }));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, tool);
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> chatClient.prompt().user("Find Alice").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("Tool authorization requires an Authentication");
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void requiresExplicitSecurityContextPropagationAcrossAnExecutorBoundary() throws Exception {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(
                        authentication.get().getName().equals("executor-user")
                )
        );
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        ToolCallback tool = factory.wrap(tool("customerLookup", ignored -> {
        }));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, tool);
        useAuthentication(authentication("executor-user"));
        ExecutorService rawExecutor = Executors.newSingleThreadExecutor();
        ExecutorService propagatedExecutor = new DelegatingSecurityContextExecutorService(
                Executors.newSingleThreadExecutor()
        );

        try {
            Future<String> unpropagated = rawExecutor.submit(
                    () -> chatClient.prompt().user("Find Alice").call().content()
            );
            assertThatThrownBy(() -> unpropagated.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(AuthorizationDeniedException.class)
                    .rootCause()
                    .hasMessage("Tool authorization requires an Authentication");

            Future<String> propagated = propagatedExecutor.submit(
                    () -> chatClient.prompt().user("Find Alice").call().content()
            );
            assertThat(propagated.get(5, TimeUnit.SECONDS)).isEqualTo("done");
        }
        finally {
            rawExecutor.shutdownNow();
            propagatedExecutor.shutdownNow();
        }

        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void supportsExplicitSecurityContextPropagationOnVirtualThreads() throws Exception {
        Assumptions.assumeTrue(Runtime.version().feature() >= 21);
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(
                        authentication.get().getName().equals("virtual-user")
                )
        );
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(service, Set.of("customerLookup"));
        ToolCallback tool = factory.wrap(tool("customerLookup", ignored -> {
        }));
        ResolvingToolLoopModel model = new ResolvingToolLoopModel(
                boundary.toolCallingManager(),
                List.of("customerLookup")
        );
        ChatClient chatClient = securedClient(service, factory, boundary, model, tool);
        useAuthentication(authentication("virtual-user"));
        ExecutorService rawVirtualExecutor = newVirtualThreadPerTaskExecutor();
        ExecutorService propagatedVirtualExecutor = new DelegatingSecurityContextExecutorService(
                newVirtualThreadPerTaskExecutor()
        );

        try {
            Future<String> unpropagated = rawVirtualExecutor.submit(
                    () -> chatClient.prompt().user("Find Alice").call().content()
            );
            assertThatThrownBy(() -> unpropagated.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(AuthorizationDeniedException.class)
                    .rootCause()
                    .hasMessage("Tool authorization requires an Authentication");

            Future<String> propagated = propagatedVirtualExecutor.submit(
                    () -> chatClient.prompt().user("Find Alice").call().content()
            );
            assertThat(propagated.get(5, TimeUnit.SECONDS)).isEqualTo("done");
        }
        finally {
            rawVirtualExecutor.shutdownNow();
            propagatedVirtualExecutor.shutdownNow();
        }

        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
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
    void toolSearchNameWithoutItsSessionContextRemainsARejectedLateCallback() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        ToolCallback injected = tool("toolSearchTool", ignored -> {
        });
        DefinitionResolvingModel model = new DefinitionResolvingModel(
                boundary.toolCallingManager()
        );
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(boundary.advisor(), lateToolInjectionAdvisor(injected))
                .defaultTools(tool("customerLookup", ignored -> {
                }))
                .build();
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find customer").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("A tool callback was added after authorization");
        assertThat(boundary.activeSessionCount()).isZero();
    }

    @Test
    void toolSearchControlIdentityCannotBeReplacedAfterAdmission() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        ToolCallback admitted = tool("toolSearchTool", ignored -> {
        });
        ToolCallback replacement = tool("toolSearchTool", ignored -> {
        });
        ToolSearchIdentityProbingModel model = new ToolSearchIdentityProbingModel(
                boundary.toolCallingManager(),
                replacement
        );
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(boundary.advisor(), toolSearchControlInjectionAdvisor(admitted))
                .defaultTools(tool("customerLookup", ignored -> {
                }))
                .build();
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find customer").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("The Tool Search control callback was replaced after admission");
        assertThat(boundary.activeSessionCount()).isZero();
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

    @Test
    @SuppressWarnings("unchecked")
    void toolSearchIndexesOnlyAuthorizedDefinitionsAndKeepsPiiTokenizedUntilBusinessExecution() {
        List<String> authorizationChecks = new ArrayList<>();
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) ->
                {
                    authorizationChecks.add(context.phase() + ":" + context.toolDefinition().name());
                    return new AuthorizationDecision(
                            context.toolDefinition().name().equals("customerLookup")
                    );
                };
        SpringSecurityToolBoundary boundary = boundary(policy);
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(
                service,
                Set.of("customerLookup", "adminDelete")
        );
        AtomicReference<String> allowedInput = new AtomicReference<>();
        AtomicInteger deniedCalls = new AtomicInteger();
        ToolCallback allowed = factory.wrap(tool("customerLookup", allowedInput::set));
        ToolCallback denied = factory.wrap(tool("adminDelete", ignored -> deniedCalls.incrementAndGet()));
        ToolIndex index = mock(ToolIndex.class);
        when(index.search(any())).thenReturn(ToolSearchResponse.builder()
                .addToolReference(ToolReference.builder()
                        .toolName("customerLookup")
                        .summary("Find a customer")
                        .build())
                .build());
        ToolSearchToolCallingAdvisor toolSearch = ToolSearchToolCallingAdvisor.builder()
                .toolIndex(index)
                .systemMessageSuffix("Search for tools before using them.")
                .toolCallingManager(boundary.toolCallingManager())
                .build();
        ToolSearchLoopModel model = new ToolSearchLoopModel(
                boundary.toolCallingManager(),
                "customerLookup"
        );
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        boundary.advisor(),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service, factory),
                        toolSearch,
                        new PrivacyToolCallValidationAdvisor(
                                service,
                                toolSearch.getOrder() + 1
                        ),
                        new PrivacyModelBoundaryAdvisor(service, factory)
                )
                .defaultTools(allowed, denied)
                .build();
        useAuthentication(authentication("alice"));

        assertThat(chatClient.prompt()
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "alice-session"))
                .user("Find Alice")
                .call()
                .content()).isEqualTo("done");
        org.mockito.ArgumentCaptor<List<ToolReference>> references =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(index).indexTools(eq("alice-session"), references.capture());
        assertThat(references.getValue())
                .extracting(ToolReference::toolName)
                .containsOnly("customerLookup");
        org.mockito.ArgumentCaptor<ToolSearchRequest> searchRequest =
                org.mockito.ArgumentCaptor.forClass(ToolSearchRequest.class);
        verify(index).search(searchRequest.capture());
        assertThat(searchRequest.getValue().sessionId()).isEqualTo("alice-session");
        assertThat(searchRequest.getValue().query()).doesNotContain("Alice");
        assertThat(PERSON_TOKEN.matcher(searchRequest.getValue().query()).find()).isTrue();
        assertThat(model.exposedToolNames().get(0)).containsOnly("toolSearchTool");
        assertThat(model.exposedToolNames().get(1))
                .containsOnly("toolSearchTool", "customerLookup");
        assertThat(allowedInput.get()).isEqualTo("{\"name\":\"Alice\"}");
        assertThat(deniedCalls).hasValue(0);
        assertThat(authorizationChecks).noneMatch(check -> check.endsWith(":toolSearchTool"));
        assertThat(model.callCount()).isEqualTo(3);
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolSearchCannotActivateAResultThatWasNotDefinitionAuthorized() {
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) ->
                new AuthorizationDecision(context.toolDefinition().name().equals("customerLookup"));
        SpringSecurityToolBoundary boundary = boundary(policy);
        PrivacyService service = privacyService();
        PrivacyToolCallbackFactory factory = privacyFactory(
                service,
                Set.of("customerLookup", "adminDelete")
        );
        AtomicInteger deniedCalls = new AtomicInteger();
        ToolCallback allowed = factory.wrap(tool("customerLookup", ignored -> {
        }));
        ToolCallback denied = factory.wrap(tool("adminDelete", ignored -> deniedCalls.incrementAndGet()));
        ToolIndex index = mock(ToolIndex.class);
        when(index.search(any())).thenReturn(ToolSearchResponse.builder()
                .addToolReference(ToolReference.builder()
                        .toolName("adminDelete")
                        .summary("Delete a customer")
                        .build())
                .build());
        ToolSearchToolCallingAdvisor toolSearch = ToolSearchToolCallingAdvisor.builder()
                .toolIndex(index)
                .systemMessageSuffix("Search for tools before using them.")
                .toolCallingManager(boundary.toolCallingManager())
                .build();
        ToolSearchLoopModel model = new ToolSearchLoopModel(
                boundary.toolCallingManager(),
                "adminDelete"
        );
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        boundary.advisor(),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service, factory),
                        toolSearch,
                        new PrivacyToolCallValidationAdvisor(
                                service,
                                toolSearch.getOrder() + 1
                        ),
                        new PrivacyModelBoundaryAdvisor(service, factory)
                )
                .defaultTools(allowed, denied)
                .build();
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt()
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, "alice-session"))
                .user("Find Alice")
                .call()
                .content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("A model-requested tool was not exposed by the authorization boundary");
        org.mockito.ArgumentCaptor<List<ToolReference>> references =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(index).indexTools(eq("alice-session"), references.capture());
        assertThat(references.getValue())
                .extracting(ToolReference::toolName)
                .containsOnly("customerLookup");
        assertThat(model.exposedToolNames().get(1)).containsOnly("toolSearchTool");
        assertThat(deniedCalls).hasValue(0);
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    private ChatClient securedClient(
            PrivacyService service,
            PrivacyToolCallbackFactory factory,
            SpringSecurityToolBoundary boundary,
            ChatModel model,
            ToolCallback... callbacks
    ) {
        ToolCallingAdvisor toolCallingAdvisor = ToolCallingAdvisor.builder()
                .toolCallingManager(boundary.toolCallingManager())
                .build();
        return ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        boundary.advisor(),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service, factory),
                        toolCallingAdvisor,
                        new PrivacyToolCallValidationAdvisor(
                                service,
                                toolCallingAdvisor.getOrder() + 1
                        ),
                        new PrivacyModelBoundaryAdvisor(service, factory)
                )
                .defaultTools((Object[]) callbacks)
                .build();
    }

    private SpringSecurityToolBoundary boundary(
            AuthorizationManager<ToolAuthorizationContext> policy
    ) {
        return SpringSecurityToolBoundary.builder(
                ToolCallingManager.builder().build(),
                policy
        ).build();
    }

    private PrivacyToolCallbackFactory privacyFactory(
            PrivacyService service,
            Set<String> disclosedTools
    ) {
        Map<String, Set<String>> disclosures = disclosedTools.stream()
                .collect(java.util.stream.Collectors.toMap(
                        name -> name,
                        ignored -> Set.of("PERSON")
                ));
        return new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.byToolName(disclosures)
        );
    }

    private PrivacyService privacyService() {
        PiiAnalyzer analyzer = (text, options) -> spans(text, "Alice").stream()
                .map(span -> new PiiSpan("PERSON", span.start(), span.end(), 0.99))
                .toList();
        return new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
    }

    private List<Span> spans(String text, String value) {
        List<Span> spans = new ArrayList<>();
        int fromIndex = 0;
        while (true) {
            int start = text.indexOf(value, fromIndex);
            if (start < 0) {
                return spans;
            }
            spans.add(new Span(start, start + value.length()));
            fromIndex = start + value.length();
        }
    }

    private ToolCallback tool(String name, java.util.function.Consumer<String> invocation) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("Synthetic authorization test tool")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public String call(String input) {
                invocation.accept(input);
                return "ok";
            }
        };
    }

    private ToolCallback contextAwareTool(
            String name,
            java.util.function.BiConsumer<String, ToolContext> invocation
    ) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(name)
                        .description("Synthetic authorization test tool")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public String call(String input) {
                throw new AssertionError("ToolContext-aware invocation was expected");
            }

            @Override
            public String call(String input, ToolContext context) {
                invocation.accept(input, context);
                return "ok";
            }
        };
    }

    private CallAdvisor lateToolInjectionAdvisor(ToolCallback callback) {
        return callbackMutationAdvisor("LateToolInjectionAdvisor", callbacks -> {
            List<ToolCallback> mutated = new ArrayList<>(callbacks);
            mutated.add(callback);
            return List.copyOf(mutated);
        });
    }

    private CallAdvisor toolSearchControlInjectionAdvisor(ToolCallback callback) {
        return new CallAdvisor() {
            @Override
            public ChatClientResponse adviseCall(
                    ChatClientRequest request,
                    CallAdvisorChain chain
            ) {
                ToolCallingChatOptions options =
                        (ToolCallingChatOptions) request.prompt().getOptions();
                ToolCallingChatOptions mutated = options.mutate()
                        .toolCallbacks(List.of(callback))
                        .toolContext("toolSearchToolSessionId", "test-session")
                        .build();
                Prompt prompt = new Prompt(request.prompt().getInstructions(), mutated);
                return chain.nextCall(request.mutate().prompt(prompt).build());
            }

            @Override
            public String getName() {
                return "ToolSearchControlInjectionAdvisor";
            }

            @Override
            public int getOrder() {
                return SpringSecurityContextAdvisor.DEFAULT_ORDER + 1;
            }
        };
    }

    private CallAdvisor callbackMutationAdvisor(
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

    private Authentication authentication(String name) {
        return new TestingAuthenticationToken(name, "credentials", "ROLE_USER");
    }

    private void useAuthentication(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    private ExecutorService newVirtualThreadPerTaskExecutor() throws Exception {
        return (ExecutorService) Executors.class
                .getMethod("newVirtualThreadPerTaskExecutor")
                .invoke(null);
    }

    private record Span(int start, int end) {
    }

    private static final class ResolvingToolLoopModel implements ChatModel {

        private final ToolCallingManager manager;
        private final List<String> requestedToolNames;
        private final List<String> exposedToolNames = new ArrayList<>();

        private ResolvingToolLoopModel(
                ToolCallingManager manager,
                List<String> requestedToolNames
        ) {
            this.manager = manager;
            this.requestedToolNames = List.copyOf(requestedToolNames);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            this.exposedToolNames.clear();
            this.exposedToolNames.addAll(this.manager.resolveToolDefinitions(options).stream()
                    .map(ToolDefinition::name)
                    .toList());
            boolean hasToolResponse = prompt.getInstructions().stream()
                    .anyMatch(ToolResponseMessage.class::isInstance);
            if (hasToolResponse) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }
            String token = prompt.getInstructions().stream()
                    .map(Message::getText)
                    .map(PERSON_TOKEN::matcher)
                    .filter(Matcher::find)
                    .map(Matcher::group)
                    .findFirst()
                    .orElse("Alice");
            List<AssistantMessage.ToolCall> calls = this.requestedToolNames.stream()
                    .map(name -> new AssistantMessage.ToolCall(
                            "call-" + name,
                            "function",
                            name,
                            "{\"name\":\"" + token + "\"}"
                    ))
                    .toList();
            AssistantMessage response = AssistantMessage.builder()
                    .content("")
                    .toolCalls(calls)
                    .build();
            return new ChatResponse(List.of(new Generation(response)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        List<String> exposedToolNames() {
            return List.copyOf(this.exposedToolNames);
        }
    }

    private static final class ToolSearchLoopModel implements ChatModel {

        private final ToolCallingManager manager;
        private final String businessToolName;
        private final List<Set<String>> exposedToolNames = new ArrayList<>();
        private final AtomicInteger callCount = new AtomicInteger();

        private ToolSearchLoopModel(
                ToolCallingManager manager,
                String businessToolName
        ) {
            this.manager = manager;
            this.businessToolName = businessToolName;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.callCount.incrementAndGet();
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            List<ToolDefinition> definitions = this.manager.resolveToolDefinitions(options);
            this.exposedToolNames.add(definitions.stream()
                    .map(ToolDefinition::name)
                    .collect(java.util.stream.Collectors.toSet()));
            boolean hasBusinessResponse = hasToolResponse(prompt, this.businessToolName);
            if (hasBusinessResponse) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }

            String token = prompt.getInstructions().stream()
                    .map(Message::getText)
                    .map(PERSON_TOKEN::matcher)
                    .filter(Matcher::find)
                    .map(Matcher::group)
                    .findFirst()
                    .orElseThrow();
            if (!hasToolResponse(prompt, "toolSearchTool")) {
                ToolDefinition controlDefinition = definitions.stream()
                        .filter(definition -> definition.name().equals("toolSearchTool"))
                        .findFirst()
                        .orElseThrow();
                String queryParameter = controlDefinition.inputSchema().contains("\"query\"")
                        ? "query"
                        : "arg0";
                return toolCallResponse(
                        "toolSearchTool",
                        "{\"" + queryParameter + "\":\"Find customer " + token + "\"}"
                );
            }
            return toolCallResponse(
                    this.businessToolName,
                    "{\"name\":\"" + token + "\"}"
            );
        }

        private static boolean hasToolResponse(Prompt prompt, String name) {
            return prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .anyMatch(response -> response.name().equals(name));
        }

        private static ChatResponse toolCallResponse(String name, String arguments) {
            AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(
                    "call-" + name,
                    "function",
                    name,
                    arguments
            );
            AssistantMessage response = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(call))
                    .build();
            return new ChatResponse(List.of(new Generation(response)));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private List<Set<String>> exposedToolNames() {
            return List.copyOf(this.exposedToolNames);
        }

        private int callCount() {
            return this.callCount.get();
        }
    }

    private static final class DefinitionResolvingModel implements ChatModel {

        private final ToolCallingManager manager;
        private List<String> exposedToolNames = List.of();

        private DefinitionResolvingModel(ToolCallingManager manager) {
            this.manager = manager;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            this.exposedToolNames = this.manager.resolveToolDefinitions(options).stream()
                    .map(ToolDefinition::name)
                    .toList();
            return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private List<String> exposedToolNames() {
            return this.exposedToolNames;
        }
    }

    private static final class ToolSearchIdentityProbingModel implements ChatModel {

        private final ToolCallingManager manager;
        private final ToolCallback replacement;

        private ToolSearchIdentityProbingModel(
                ToolCallingManager manager,
                ToolCallback replacement
        ) {
            this.manager = manager;
            this.replacement = replacement;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
            this.manager.resolveToolDefinitions(options);
            ToolCallingChatOptions replaced = options.mutate()
                    .toolCallbacks(List.of(this.replacement))
                    .build();
            this.manager.resolveToolDefinitions(replaced);
            throw new AssertionError("Tool Search callback replacement should fail closed");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private static final class NeverStreamingModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new AssertionError("Streaming invocation was expected");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.never();
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }
}
