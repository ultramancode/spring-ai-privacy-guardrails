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
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class SecurityToolBoundaryTestFixtures {

    private static final Pattern PERSON_TOKEN =
            OpaquePiiTokenFormat.patternForEntityType("PERSON");

    private SecurityToolBoundaryTestFixtures() {
    }

    static ChatClient securedClient(
            PrivacyService service,
            PrivacyToolCallbackFactory factory,
            SpringSecurityToolBoundary boundary,
            ChatModel model,
            ToolCallback... callbacks
    ) {
        // Mirror production wiring: the security advisor captures request state, while
        // ToolCallingAdvisor uses the paired authorization-aware manager.
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
                        // Validate model-generated tool calls immediately inside the tool loop.
                        new PrivacyToolCallValidationAdvisor(
                                service,
                                toolCallingAdvisor.getOrder() + 1
                        ),
                        new PrivacyModelBoundaryAdvisor(service, factory)
                )
                .defaultTools((Object[]) callbacks)
                .build();
    }

    static SpringSecurityToolBoundary boundary(
            AuthorizationManager<ToolAuthorizationContext> policy
    ) {
        return SpringSecurityToolBoundary.builder(
                ToolCallingManager.builder().build(),
                policy
        ).build();
    }

    static PrivacyToolCallbackFactory privacyFactory(
            PrivacyService service,
            Set<String> disclosedTools
    ) {
        Map<String, Set<String>> disclosures = disclosedTools.stream()
                .collect(Collectors.toMap(
                        name -> name,
                        ignored -> Set.of("PERSON")
                ));
        return new PrivacyToolCallbackFactory(
                service,
                ToolDisclosurePolicy.byToolName(disclosures)
        );
    }

    static PrivacyService privacyService() {
        PiiAnalyzer analyzer = (text, options) -> spans(text, "Alice").stream()
                .map(span -> new PiiSpan("PERSON", span.start(), span.end(), 0.99))
                .toList();
        return new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
    }

    private static List<Span> spans(String text, String value) {
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

    static ToolCallback tool(String name, Consumer<String> invocation) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return toolDefinition(name);
            }

            @Override
            public String call(String input) {
                invocation.accept(input);
                return "ok";
            }
        };
    }

    static ToolCallback contextAwareTool(
            String name,
            BiConsumer<String, ToolContext> invocation
    ) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return toolDefinition(name);
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

    private static ToolDefinition toolDefinition(String name) {
        return ToolDefinition.builder()
                .name(name)
                .description("Synthetic authorization test tool")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}")
                .build();
    }

    static Authentication authentication(String name) {
        return new TestingAuthenticationToken(name, "credentials", "ROLE_USER");
    }

    static void useAuthentication(Authentication authentication) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
    }

    static CallAdvisor lateToolInjectionAdvisor(ToolCallback callback) {
        return new CallAdvisor() {
            @Override
            public ChatClientResponse adviseCall(
                    ChatClientRequest request,
                    CallAdvisorChain chain
            ) {
                ToolCallingChatOptions options =
                        (ToolCallingChatOptions) request.prompt().getOptions();
                List<ToolCallback> callbacks = new ArrayList<>(options.getToolCallbacks());
                callbacks.add(callback);
                ToolCallingChatOptions mutated = options.mutate()
                        .toolCallbacks(List.copyOf(callbacks))
                        .build();
                Prompt prompt = new Prompt(request.prompt().getInstructions(), mutated);
                return chain.nextCall(request.mutate().prompt(prompt).build());
            }

            @Override
            public String getName() {
                return "LateToolInjectionAdvisor";
            }

            @Override
            public int getOrder() {
                return SpringSecurityContextAdvisor.DEFAULT_ORDER + 1;
            }
        };
    }

    private record Span(int start, int end) {
    }

    // Resolves and records the definitions exposed for one model call.
    static final class DefinitionResolvingModel implements ChatModel {

        private final ToolCallingManager manager;
        private List<String> exposedToolNames = List.of();

        DefinitionResolvingModel(ToolCallingManager manager) {
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

        List<String> exposedToolNames() {
            return this.exposedToolNames;
        }
    }

    // Records exposed definitions, emits one batch of calls for the configured tool names,
    // then returns a final response.
    static final class ResolvingToolLoopModel implements ChatModel {

        private final ToolCallingManager manager;
        private final List<String> requestedToolNames;
        private final List<String> exposedToolNames = new ArrayList<>();

        ResolvingToolLoopModel(
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
}
