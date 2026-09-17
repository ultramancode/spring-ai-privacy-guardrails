package io.github.ultramancode.springai.privacy.inspection.springai;

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
import io.github.ultramancode.springai.privacy.security.autoconfigure.PrivacySecurityChatClientFactory;
import io.github.ultramancode.springai.privacy.security.autoconfigure.ToolAuthorizationAutoConfiguration;
import io.github.ultramancode.springai.privacy.security.autoconfigure.ToolAuthorizationChatClientFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyModelBoundaryAdvisor;
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
import java.util.function.UnaryOperator;
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
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PrivacyGuardrailsAutoConfiguration.class,
                        ToolAuthorizationAutoConfiguration.class,
                        PrivacySecurityAutoConfiguration.class))
                .withUserConfiguration(Policy.class)
                .withBean(ToolCallingManager.class, () -> ToolCallingManager.builder().build())
                .withBean(PiiAnalyzer.class, () -> (text, options) -> {
                    int start = text.indexOf("Alice");
                    return start < 0 ? List.of() : List.of(new PiiSpan("PERSON", start, start + 5, 1.0));
                });
    }

    @ParameterizedTest(name = "streaming={0}, toolSearch={1}")
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void combinedFactoryInspectsProtectedToolResults(boolean streaming, boolean search) {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var inspected = new CopyOnWriteArrayList<InspectionRequest>();
            AtomicInteger modelCalls = new AtomicInteger();
            var client = context.getBean(PrivacySecurityChatClientFactory.class)
                    .builder(toolCallingModel(search, modelCalls), toolAdvisorBuilder(search),
                            inspectionConfigurer(inspected))
                    .defaultTools(context.getBean(PrivacyToolCallbackFactory.class).wrap(lookupTool()))
                    .build();
            var authentication = UsernamePasswordAuthenticationToken.authenticated(
                    "test", "unused", List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            try {
                var request = client.prompt().user("Hello Alice")
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
                assertThat(modelCalls).hasValue(search ? 2 : 1);
                assertThat(inspected).hasSize(search ? 3 : 2);
                assertThat(inspected.get(inspected.size() - 1).segments())
                        .anyMatch(s -> s.source() == ContentSegment.Source.TOOL && s.text().contains("attack"));
            } finally {
                SecurityContextHolder.clearContext();
            }
        });
    }

    @ParameterizedTest(name = "streaming={0}, privacyEnabled={1}")
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void clonedTerminalBuilderStillBlocksBeforeModelCall(boolean streaming, boolean privacyEnabled) {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            AtomicInteger modelCalls = new AtomicInteger();
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    modelCalls.incrementAndGet();
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.defer(() -> Flux.just(call(prompt)));
                }
            };
            var inspection = new InspectionChatClientConfigurer(new InspectionService(List.of(
                    new RuleBasedContentInspector(List.of(InspectionRule.literal("attack", "attack"))))));
            UnaryOperator<ChatClient.Builder> configureClone =
                    builder -> inspection.configure(builder.clone());
            ChatClient.Builder builder = privacyEnabled
                    ? context.getBean(PrivacySecurityChatClientFactory.class)
                            .builderWithTerminalBoundary(model, configureClone)
                    : context.getBean(ToolAuthorizationChatClientFactory.class)
                            .builderWithTerminalBoundary(model, configureClone);
            var request = builder.build().prompt().user("Alice attack");

            if (streaming) {
                assertThatThrownBy(() -> request.stream().content().collectList().block(Duration.ofSeconds(5)))
                        .isInstanceOf(InspectionBlockedException.class);
            } else {
                assertThatThrownBy(() -> request.call().content())
                        .isInstanceOf(InspectionBlockedException.class);
            }
            assertThat(modelCalls).hasValue(0);
        });
    }

    private InspectionChatClientConfigurer inspectionConfigurer(List<InspectionRequest> inspected) {
        ContentInspector inspector = request -> {
            request.requireProtected();
            assertThat(request.segments())
                    .allSatisfy(segment -> assertThat(segment.text()).doesNotContain("Alice"));
            inspected.add(request);
            var findings = request.segments().stream()
                    .filter(s -> s.text().contains("attack"))
                    .map(s -> new InspectionFinding(s.id(), InspectionFinding.Category.PROMPT_ATTACK, "attack", null))
                    .toList();
            return InspectionResult.completed(
                    request.segments().stream().map(ContentSegment::id).collect(Collectors.toSet()), findings);
        };
        return new InspectionChatClientConfigurer(
                new InspectionService(List.of(inspector)),
                InspectionLimits.defaults(),
                request -> PrivacyModelBoundaryAdvisor.isModelContentProtected(request)
                        ? ContentSegment.Representation.PRIVACY_PROTECTED
                        : ContentSegment.Representation.AS_RECEIVED,
                ignored -> {});
    }

    private ChatModel toolCallingModel(boolean search, AtomicInteger modelCalls) {
        return new ChatModel() {
            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                int round = modelCalls.incrementAndGet();
                if (search && round == 1) {
                    return toolResponse("toolSearchTool", "{\"query\":\"lookup\"}");
                }
                return toolResponse("lookup", "{}");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> Flux.just(call(prompt)));
            }
        };
    }

    private ToolCallingAdvisor.Builder<?> toolAdvisorBuilder(boolean search) {
        if (!search) {
            return ToolCallingAdvisor.builder();
        }
        ToolIndex index = mock(ToolIndex.class);
        when(index.search(any())).thenReturn(ToolSearchResponse.builder()
                .addToolReference(ToolReference.builder().toolName("lookup").summary("Lookup").build())
                .build());
        return ToolSearchToolCallingAdvisor.builder().toolIndex(index).systemMessageSuffix("Search for tools.");
    }

    private ToolCallback lookupTool() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("Lookup").inputSchema("{}").build();
            }

            @Override
            public String call(String arguments) {
                return "Alice attack";
            }
        };
    }

    private static ChatResponse toolResponse(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + name, "function", name, arguments)))
                .build())));
    }
}
