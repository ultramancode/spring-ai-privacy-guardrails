package io.github.ultramancode.springai.privacy.security;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
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

    private static final Pattern PERSON_TOKEN_PATTERN =
        OpaquePiiTokenFormat.patternForEntityType("PERSON");

    private SecurityToolBoundaryTestFixtures() {
    }

    static ChatClient securedClient(
        PrivacyService privacyService,
        PrivacyToolCallbackFactory toolCallbackFactory,
        SpringSecurityToolBoundary toolAuthorizationBoundary,
        ChatModel chatModel,
        ToolCallback... toolCallbacks
    ) {
        PrivacyChatClientConfigurer privacyConfigurer =
            new PrivacyChatClientConfigurer(privacyService, toolCallbackFactory);
        ToolAuthorizationChatClientFactory toolAuthorizationFactory = ToolAuthorizationChatClientFactory.builder()
            .toolAuthorizationBoundary(toolAuthorizationBoundary)
            .build();
        return new PrivacySecurityChatClientFactory(privacyConfigurer, toolAuthorizationFactory)
            .builder(chatModel)
            .defaultTools((Object[]) toolCallbacks)
            .build();
    }

    static SpringSecurityToolBoundary boundary(
        AuthorizationManager<ToolAuthorizationContext> authorizationManager
    ) {
        return SpringSecurityToolBoundary.builder(
            ToolCallingManager.builder().build(),
            authorizationManager
        ).build();
    }

    static PrivacyToolCallbackFactory privacyFactory(
        PrivacyService privacyService,
        Set<String> disclosedToolNames
    ) {
        Map<String, Set<String>> entityTypesByToolName = disclosedToolNames.stream()
            .collect(Collectors.toMap(
                toolName -> toolName,
                ignored -> Set.of("PERSON")
            ));
        return new PrivacyToolCallbackFactory(
            privacyService,
            ToolDisclosurePolicy.allowEntityTypesByToolName(entityTypesByToolName)
        );
    }

    static PrivacyService privacyService() {
        PiiAnalyzer analyzer = (text, options, processingLimits) -> personSpans(text);
        return new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
    }

    private static List<PiiSpan> personSpans(String text) {
        List<PiiSpan> personSpans = new ArrayList<>();
        String personName = "Alice";
        int start = text.indexOf(personName);
        while (start >= 0) {
            int end = start + personName.length();
            personSpans.add(new PiiSpan("PERSON", start, end, 0.99));
            start = text.indexOf(personName, end);
        }
        return personSpans;
    }

    static ToolCallback tool(String toolName, Consumer<String> invocation) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return toolDefinition(toolName);
            }

            @Override
            public String call(String input) {
                invocation.accept(input);
                return "ok";
            }
        };
    }

    static ToolCallback contextAwareTool(
        String toolName,
        BiConsumer<String, ToolContext> invocation
    ) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return toolDefinition(toolName);
            }

            @Override
            public String call(String input) {
                throw new AssertionError("ToolContext-aware invocation was expected");
            }

            @Override
            public String call(String input, ToolContext toolContext) {
                invocation.accept(input, toolContext);
                return "ok";
            }
        };
    }

    private static ToolDefinition toolDefinition(String toolName) {
        return ToolDefinition.builder()
            .name(toolName)
            .description("Synthetic authorization test tool")
            .inputSchema("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}")
            .build();
    }

    static Authentication authentication(String username) {
        return new TestingAuthenticationToken(username, "credentials", "ROLE_USER");
    }

    static void useAuthentication(Authentication authentication) {
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(authentication);
        SecurityContextHolder.setContext(securityContext);
    }

    static CallAdvisor lateToolInjectionAdvisor(ToolCallback toolCallback) {
        return new CallAdvisor() {
            @Override
            public ChatClientResponse adviseCall(
                ChatClientRequest request,
                CallAdvisorChain chain
            ) {
                ToolCallingChatOptions toolCallingOptions =
                    (ToolCallingChatOptions) request.prompt().getOptions();
                List<ToolCallback> updatedToolCallbacks = new ArrayList<>(toolCallingOptions.getToolCallbacks());
                updatedToolCallbacks.add(toolCallback);
                ToolCallingChatOptions updatedToolCallingOptions = toolCallingOptions.mutate()
                    .toolCallbacks(List.copyOf(updatedToolCallbacks))
                    .build();
                Prompt updatedPrompt = new Prompt(request.prompt().getInstructions(), updatedToolCallingOptions);
                return chain.nextCall(request.mutate().prompt(updatedPrompt).build());
            }

            @Override
            public String getName() {
                return "LateToolInjectionAdvisor";
            }

            @Override
            public int getOrder() {
                return ToolAuthorizationLifecycleAdvisor.DEFAULT_ORDER + 1;
            }
        };
    }

    // Resolves and records the definitions exposed for one model call.
    static final class DefinitionResolvingModel implements ChatModel {

        private final ToolCallingManager toolCallingManager;
        private List<String> exposedToolNames = List.of();

        DefinitionResolvingModel(ToolCallingManager toolCallingManager) {
            this.toolCallingManager = toolCallingManager;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ToolCallingChatOptions toolCallingOptions = (ToolCallingChatOptions) prompt.getOptions();
            this.exposedToolNames = this.toolCallingManager.resolveToolDefinitions(toolCallingOptions).stream()
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

        private final ToolCallingManager toolCallingManager;
        private final List<String> requestedToolNames;
        private List<String> exposedToolNames = List.of();

        ResolvingToolLoopModel(
            ToolCallingManager toolCallingManager,
            List<String> requestedToolNames
        ) {
            this.toolCallingManager = toolCallingManager;
            this.requestedToolNames = List.copyOf(requestedToolNames);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            ToolCallingChatOptions toolCallingOptions = (ToolCallingChatOptions) prompt.getOptions();
            this.exposedToolNames = this.toolCallingManager.resolveToolDefinitions(toolCallingOptions).stream()
                .map(ToolDefinition::name)
                .toList();
            boolean hasToolResponse = prompt.getInstructions().stream()
                .anyMatch(ToolResponseMessage.class::isInstance);
            if (hasToolResponse) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }
            // Use the raw test name when privacy tokenization is not configured.
            String toolArgumentName = "Alice";
            for (Message message : prompt.getInstructions()) {
                Matcher personTokenMatcher = PERSON_TOKEN_PATTERN.matcher(message.getText());
                if (personTokenMatcher.find()) {
                    toolArgumentName = personTokenMatcher.group();
                    break;
                }
            }
            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
            for (String toolName : this.requestedToolNames) {
                toolCalls.add(new AssistantMessage.ToolCall(
                    "call-" + toolName,
                    "function",
                    toolName,
                    "{\"name\":\"" + toolArgumentName + "\"}"
                ));
            }
            AssistantMessage assistantMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(toolCalls)
                .build();
            return new ChatResponse(List.of(new Generation(assistantMessage)));
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
}
