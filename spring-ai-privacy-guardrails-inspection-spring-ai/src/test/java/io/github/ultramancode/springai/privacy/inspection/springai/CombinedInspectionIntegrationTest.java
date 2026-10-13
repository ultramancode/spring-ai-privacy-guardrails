package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import io.github.ultramancode.springai.privacy.security.autoconfigure.PrivacySecurityAutoConfiguration;
import io.github.ultramancode.springai.privacy.security.PrivacySecurityChatClientFactory;
import io.github.ultramancode.springai.privacy.security.autoconfigure.ToolAuthorizationAutoConfiguration;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationChatClientFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.toolsearch.ToolSearchToolCallingAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import org.springframework.ai.tool.toolsearch.ToolSearchResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CombinedInspectionIntegrationTest {

    @Configuration(proxyBeanMethods = false)
    static class Policy {
        @Bean
        AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
            return (authentication, context) -> new AuthorizationDecision(true);
        }
    }

    private ApplicationContextRunner runner() {
        PiiAnalyzer analyzer = (text, options, limits) -> {
            int start = text.indexOf("Alice");
            return start < 0 ? List.of() : List.of(new PiiSpan("PERSON", start, start + 5, 1.0));
        };

        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PrivacyGuardrailsAutoConfiguration.class,
                        ToolAuthorizationAutoConfiguration.class,
                        PrivacySecurityAutoConfiguration.class))
                .withUserConfiguration(Policy.class)
                .withBean(ToolCallingManager.class, () -> ToolCallingManager.builder().build())
                .withBean(PiiAnalyzer.class, () -> analyzer);
    }

    private InspectionChatClientConfigurer attackBlockingInspectionConfigurer(List<InspectionRequest> inspectionRequests) {
        ContentInspector inspector = new ContentInspector() {
            @Override
            public String inspectorId() {
                return "protected-content";
            }

            @Override
            public boolean requiresPrivacyProcessedContent() {
                return true;
            }

            @Override
            public InspectionResult inspect(InspectionRequest request) {
                request.requirePrivacyProcessed();
                assertThat(request.segments())
                        .allSatisfy(segment -> assertThat(segment.text()).doesNotContain("Alice"));
                inspectionRequests.add(request);
                List<InspectionFinding> findings = request.segments().stream()
                        .filter(segment -> segment.text().contains("attack"))
                        .map(segment -> new InspectionFinding(segment.id(), InspectionFinding.Category.PROMPT_ATTACK, "attack", null))
                        .toList();
                return InspectionResult.completed(
                        request.segments().stream().map(ContentSegment::id).collect(Collectors.toSet()), findings);
            }
        };
        return new InspectionChatClientConfigurer(
                new InspectionService(List.of(inspector)),
                InspectionLimits.defaults(),
                request -> PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(request)
                        ? ContentSegment.PrivacyProcessingStatus.PROCESSED
                        : ContentSegment.PrivacyProcessingStatus.UNKNOWN,
                ignored -> {});
    }

    private static ChatResponse toolCallResponse(String toolName, String toolArgumentsJson) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + toolName, "function", toolName, toolArgumentsJson)))
                .build())));
    }

    @ParameterizedTest(name = "streaming={0}, toolSearch={1}")
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void combinedFactoryBlocksToolResultsAfterPrivacyProcessing(boolean streaming, boolean toolSearchEnabled) {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            List<InspectionRequest> inspectionRequests = new CopyOnWriteArrayList<>();
            AtomicInteger modelCallCount = new AtomicInteger();
            ChatModel model = new ChatModel() {
                @Override
                public ChatOptions getOptions() {
                    return ToolCallingChatOptions.builder().build();
                }

                @Override
                public ChatResponse call(Prompt prompt) {
                    int round = modelCallCount.incrementAndGet();
                    if (toolSearchEnabled && round == 1) {
                        return toolCallResponse("toolSearchTool", "{\"query\":\"lookup\"}");
                    }
                    return toolCallResponse("lookup", "{}");
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.defer(() -> Flux.just(call(prompt)));
                }
            };
            ToolCallingAdvisor.Builder<?> toolAdvisorBuilder = ToolCallingAdvisor.builder();
            if (toolSearchEnabled) {
                ToolIndex index = mock(ToolIndex.class);
                when(index.search(any())).thenReturn(ToolSearchResponse.builder()
                        .addToolReference(ToolReference.builder().toolName("lookup").summary("Lookup").build())
                        .build());
                toolAdvisorBuilder = ToolSearchToolCallingAdvisor.builder()
                        .toolIndex(index).systemMessageSuffix("Search for tools.");
            }
            ToolCallback lookupTool = new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name("lookup").description("Lookup").inputSchema("{}").build();
                }

                @Override
                public String call(String arguments) {
                    return "Alice attack";
                }
            };

            ChatClient client = context.getBean(PrivacySecurityChatClientFactory.class)
                    .builder(model, toolAdvisorBuilder, attackBlockingInspectionConfigurer(inspectionRequests))
                    .defaultTools(context.getBean(PrivacyToolCallbackFactory.class).wrap(lookupTool))
                    .build();
            UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                    "test", "unused", List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                ChatClient.ChatClientRequestSpec request = client.prompt().user("Hello Alice")
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "fixture"));
                if (streaming) {
                    assertThatThrownBy(() -> request.stream().content()
                            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                            .collectList()
                            .block(Duration.ofSeconds(10)))
                            .isInstanceOf(InspectionBlockedException.class);
                } else {
                    assertThatThrownBy(() -> request.call().content())
                            .isInstanceOf(InspectionBlockedException.class);
                }
                int expectedModelCalls = toolSearchEnabled ? 2 : 1;
                assertThat(modelCallCount).hasValue(expectedModelCalls);
                // The blocked request is recorded by the inspector but never increments modelCallCount.
                assertThat(inspectionRequests).hasSize(expectedModelCalls + 1);
                InspectionRequest lastInspectionRequest =
                        inspectionRequests.get(inspectionRequests.size() - 1);
                assertThat(lastInspectionRequest.segments())
                        .anyMatch(segment -> segment.role() == ContentSegment.Role.TOOL && segment.text().contains("attack"));
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    @ParameterizedTest(name = "streaming={0}, privacyEnabled={1}")
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void clonedBoundaryBuilderStillBlocksBeforeModelCall(boolean streaming, boolean privacyEnabled) {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            AtomicInteger modelCallCount = new AtomicInteger();
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    modelCallCount.incrementAndGet();
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.defer(() -> Flux.just(call(prompt)));
                }
            };
            InspectionRule rule = InspectionRule.literal("attack-rule", InspectionFinding.Category.PROMPT_INJECTION, "attack");
            ContentInspector inspector = new RuleBasedContentInspector("application-rules", List.of(rule));
            InspectionChatClientConfigurer inspectionChatClientConfigurer = new InspectionChatClientConfigurer(new InspectionService(List.of(inspector)));
            ChatClient.Builder builder;
            if (privacyEnabled) {
                builder = context.getBean(PrivacySecurityChatClientFactory.class).builderWithBoundary(model, inspectionChatClientConfigurer);
            } else {
                builder = context.getBean(ToolAuthorizationChatClientFactory.class).builderWithBoundary(model, inspectionChatClientConfigurer);
            }
            ChatClient.ChatClientRequestSpec request = builder.clone().build().prompt().user("Alice attack");

            if (streaming) {
                assertThatThrownBy(() -> request.stream().content().collectList().block(Duration.ofSeconds(5)))
                        .isInstanceOf(InspectionBlockedException.class);
            } else {
                assertThatThrownBy(() -> request.call().content())
                        .isInstanceOf(InspectionBlockedException.class);
            }
            assertThat(modelCallCount).hasValue(0);
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void privacyAndInspectionAffectOnlyConfiguredClients(boolean streaming) {
        PiiAnalyzer analyzer = (text, options, limits) -> {
            int start = text.indexOf("Alice");
            return start < 0 ? List.of() : List.of(new PiiSpan("PERSON", start, start + 5, 1.0));
        };
        PrivacyService privacyService = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        List<InspectionRequest> inspectionRequests = new CopyOnWriteArrayList<>();
        List<String> modelInputTexts = new CopyOnWriteArrayList<>();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                modelInputTexts.add(prompt.getContents());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> Flux.just(call(prompt)));
            }
        };
        ChatClient protectedClient = ModelRequestBoundaryConfigurer.compose(
                attackBlockingInspectionConfigurer(inspectionRequests), new PrivacyChatClientConfigurer(privacyService))
                .configure(ChatClient.builder(model)).build();
        ChatClient unprotectedClient = ChatClient.builder(model).build();
        for (ChatClient client : List.of(protectedClient, unprotectedClient)) {
            ChatClient.ChatClientRequestSpec request = client.prompt().user("Alice");
            if (streaming) {
                request.stream().content().collectList().block(Duration.ofSeconds(5));
            } else {
                request.call().content();
            }
        }
        assertThat(inspectionRequests).hasSize(1);
        assertThat(modelInputTexts.get(0)).doesNotContain("Alice");
        assertThat(modelInputTexts.get(1)).isEqualTo("Alice");
    }
}
