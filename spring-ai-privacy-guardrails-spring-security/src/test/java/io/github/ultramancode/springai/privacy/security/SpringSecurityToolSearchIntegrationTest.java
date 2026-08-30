package io.github.ultramancode.springai.privacy.security;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.springai.PrivacyInputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyLifecycleAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyModelBoundaryAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallValidationAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolContextAdvisor;
import io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.DefinitionResolvingModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.authentication;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.boundary;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.lateToolInjectionAdvisor;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.privacyFactory;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.privacyService;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.tool;
import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.useAuthentication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpringSecurityToolSearchIntegrationTest {

    private static final Pattern PERSON_TOKEN =
            OpaquePiiTokenFormat.patternForEntityType("PERSON");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsAToolSearchNamedCallbackWithoutTheSessionMarker() {
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
    void rejectsReplacingTheToolSearchControlCallbackDuringARequest() {
        SpringSecurityToolBoundary boundary = boundary(
                (authentication, context) -> new AuthorizationDecision(true)
        );
        ToolCallback control = tool("toolSearchTool", ignored -> {
        });
        ToolCallback replacement = tool("toolSearchTool", ignored -> {
        });
        ToolSearchCallbackReplacementModel model = new ToolSearchCallbackReplacementModel(
                boundary.toolCallingManager(),
                replacement
        );
        ChatClient chatClient = ChatClient.builder(model)
                .defaultAdvisors(boundary.advisor(), toolSearchControlInjectionAdvisor(control))
                .defaultTools(tool("customerLookup", ignored -> {
                }))
                .build();
        useAuthentication(authentication("alice"));

        assertThatThrownBy(() -> chatClient.prompt().user("Find customer").call().content())
                .isInstanceOf(AuthorizationDeniedException.class)
                .hasMessage("The Tool Search control callback changed during the request");
        assertThat(boundary.activeSessionCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolSearchIndexesOnlyAuthorizedDefinitionsAndKeepsPiiTokenizedUntilBusinessExecution() {
        List<String> authorizationChecks = new ArrayList<>();
        AuthorizationManager<ToolAuthorizationContext> policy = (authentication, context) -> {
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
        ToolCallback denied = factory.wrap(tool(
                "adminDelete",
                ignored -> deniedCalls.incrementAndGet()
        ));
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
        ArgumentCaptor<List<ToolReference>> references = ArgumentCaptor.forClass(List.class);
        verify(index).indexTools(eq("alice-session"), references.capture());
        assertThat(references.getValue())
                .extracting(ToolReference::toolName)
                .containsOnly("customerLookup");
        ArgumentCaptor<ToolSearchRequest> searchRequest =
                ArgumentCaptor.forClass(ToolSearchRequest.class);
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
    void rejectsAToolSearchResultThatWasNotDefinitionAuthorized() {
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
        ToolCallback denied = factory.wrap(tool(
                "adminDelete",
                ignored -> deniedCalls.incrementAndGet()
        ));
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
        ArgumentCaptor<List<ToolReference>> references = ArgumentCaptor.forClass(List.class);
        verify(index).indexTools(eq("alice-session"), references.capture());
        assertThat(references.getValue())
                .extracting(ToolReference::toolName)
                .containsOnly("customerLookup");
        assertThat(model.exposedToolNames().get(1)).containsOnly("toolSearchTool");
        assertThat(deniedCalls).hasValue(0);
        assertThat(boundary.activeSessionCount()).isZero();
        assertThat(service.activeSessionCount()).isZero();
    }

    private static CallAdvisor toolSearchControlInjectionAdvisor(ToolCallback callback) {
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
                    .collect(Collectors.toSet()));
            if (hasToolResponse(prompt, this.businessToolName)) {
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

    private static final class ToolSearchCallbackReplacementModel implements ChatModel {

        private final ToolCallingManager manager;
        private final ToolCallback replacement;

        private ToolSearchCallbackReplacementModel(
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
}
