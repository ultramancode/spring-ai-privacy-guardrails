package io.github.ultramancode.springai.privacy.chatclient.autoconfigure;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.autoconfigure.InspectionAutoConfiguration;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.security.SpringSecurityToolBoundary;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationChatClientFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAction;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authorization.AuthorizationDecision;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardrailsAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(PrivacyGuardrailsAutoConfiguration.class, InspectionAutoConfiguration.class,
            GuardrailsAutoConfiguration.class));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultPolicyAndMutatedPolicyWorkWithoutSecurity(boolean streaming) {
        EchoModel chatModel = new EchoModel("Alice");
        AtomicInteger observations = new AtomicInteger();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context context) { return true; }
            public void onStop(Observation.Context context) { observations.incrementAndGet(); }
        });
        runner.withClassLoader(new FilteredClassLoader("org.springframework.security",
            "io.github.ultramancode.springai.privacy.security"))
            .withBean(PrivacyService.class, GuardrailsAutoConfigurationTest::privacy)
            .withBean(ObservationRegistry.class, () -> observationRegistry)
            .withBean(ChatClientBuilderCustomizer.class, () -> chatClientBuilder -> chatClientBuilder.defaultSystem("Be concise"))
            .withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder()
                .privacy(privacyPolicyBuilder -> privacyPolicyBuilder.outputAction(PrivacyOutputAction.REDACT)).build())
            .run(context -> {
                assertThat(context).hasNotFailed();
                GuardrailsChatClientFactory guardrailsChatClientFactory = context.getBean(GuardrailsChatClientFactory.class);
                GuardrailsPolicy inputOnly = context.getBean(GuardrailsPolicy.class).mutate()
                    .privacy(privacyPolicyBuilder -> privacyPolicyBuilder.outputProtection(false)).build();
                assertThat(invoke(guardrailsChatClientFactory.builder(chatModel).build(), streaming))
                    .isEqualTo("[REDACTED_PERSON]");
                assertThat(invoke(guardrailsChatClientFactory.builder(chatModel, inputOnly).build(), streaming))
                    .isEqualTo("Alice");
                assertThat(invoke(guardrailsChatClientFactory.builder(chatModel).build(), streaming))
                    .isEqualTo("[REDACTED_PERSON]");
                assertThat(chatModel.lastPrompt.getSystemMessage().getText()).isEqualTo("Be concise");
                assertThat(observations).hasValueGreaterThan(0);
            });
    }

    @Test
    void customizerCanOverrideTheDefaultToolCallingSetting() {
        ChatModel chatModel = new ChatModel() {
            public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                AssistantMessage message = AssistantMessage.builder().content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}")))
                    .build();
                return new ChatResponse(List.of(new Generation(message)));
            }
        };
        ToolCallback lookup = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("Look up a record")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}").build();
            }

            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(true).build();
            }

            public String call(String input) {
                return "found";
            }
        };
        runner.withBean(PrivacyService.class, GuardrailsAutoConfigurationTest::privacy)
            .withBean(ChatClientBuilderCustomizer.class, () -> chatClientBuilder ->
                chatClientBuilder.defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(true)))
            .withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder().privacy().build())
            .withPropertyValues("spring.ai.chat.client.tool-calling.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                ChatClient chatClient = context.getBean(GuardrailsChatClientFactory.class).builder(chatModel)
                    .defaultTools(lookup).build();
                assertThat(chatClient.prompt().user("Find the record").call().content()).isEqualTo("found");
            });
    }

    @Test
    void componentsDoNotSelectFeaturesAndAmbiguousDefaultsAllowExplicitPolicies() {
        runner.withBean(PrivacyService.class, GuardrailsAutoConfigurationTest::privacy).run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(GuardrailsPolicy.class);
            GuardrailsChatClientFactory guardrailsChatClientFactory = context.getBean(GuardrailsChatClientFactory.class);
            assertThatThrownBy(() -> guardrailsChatClientFactory.builder(new EchoModel())).hasMessageContaining("No default");
        });
        runner.withBean(PrivacyService.class, GuardrailsAutoConfigurationTest::privacy)
            .withBean("first", GuardrailsPolicy.class, () -> GuardrailsPolicy.builder().privacy().build())
            .withBean("second", GuardrailsPolicy.class, () -> GuardrailsPolicy.builder().privacy().build())
            .run(context -> {
                assertThat(context).hasNotFailed();
                GuardrailsChatClientFactory guardrailsChatClientFactory = context.getBean(GuardrailsChatClientFactory.class);
                assertThatThrownBy(() -> guardrailsChatClientFactory.builder(new EchoModel())).hasMessageContaining("No default");
                ChatClient chatClient = guardrailsChatClientFactory.builder(new EchoModel(),
                    GuardrailsPolicy.builder().privacy().build()).build();
                assertThat(chatClient.prompt().user("Alice").call().content())
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
            });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectsInputAndOutputInspectionFromPolicyBean(boolean streaming) {
        AtomicInteger inspections = new AtomicInteger();
        ContentInspector inspector = new ContentInspector() {
            public String inspectorId() { return "rules"; }
            public boolean requiresPrivacyProcessedContent() { return false; }
            public InspectionResult inspect(InspectionRequest request) {
                inspections.incrementAndGet();
                return InspectionResult.completed(request.segments().stream().map(ContentSegment::id)
                    .collect(Collectors.toSet()), List.of());
            }
        };
        runner.withBean(ContentInspector.class, () -> inspector)
            .withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder()
                .inspection(inspectionPolicyBuilder -> inspectionPolicyBuilder
                    .applicationOutputInspectionEnabled(true)).build())
            .run(context -> {
                assertThat(context).hasNotFailed();
                ChatClient chatClient = context.getBean(GuardrailsChatClientFactory.class).builder(new EchoModel()).build();
                assertThat(invoke(chatClient, streaming)).isEqualTo("Alice");
                assertThat(inspections).hasValue(2);
            });
    }

    @Test
    void connectsTheOptionalAuthorizationFactoryAndReportsMissingComponents() {
        AtomicInteger securityClientCustomizations = new AtomicInteger();
        ToolAuthorizationChatClientFactory toolAuthorizationChatClientFactory = new ToolAuthorizationChatClientFactory(
            SpringSecurityToolBoundary.builder(ToolCallingManager.builder().build(),
                (authentication, context) -> new AuthorizationDecision(true)).build(),
            ToolCallingAdvisor.builder(), ObservationRegistry.NOOP, null, null, chatClientBuilder -> {
                securityClientCustomizations.incrementAndGet();
                return chatClientBuilder;
            }, true);
        EchoModel chatModel = new EchoModel();
        AtomicInteger guardrailsClientCustomizations = new AtomicInteger();
        runner.withBean(ToolAuthorizationChatClientFactory.class, () -> toolAuthorizationChatClientFactory)
            .withBean(ChatClientBuilderCustomizer.class, () -> chatClientBuilder -> {
                guardrailsClientCustomizations.incrementAndGet();
                chatClientBuilder.defaultSystem("Be concise");
            })
            .withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder().toolAuthorization(true).build())
            .run(context -> {
                assertThat(context).hasNotFailed();
                ChatClient chatClient = context.getBean(GuardrailsChatClientFactory.class).builder(chatModel).build();
                assertThat(chatClient.prompt().user("hello").call().content()).isEqualTo("hello");
                assertThat(chatModel.lastPrompt.getSystemMessage().getText()).isEqualTo("Be concise");
                assertThat(guardrailsClientCustomizations).hasValue(1);
                assertThat(securityClientCustomizations).hasValue(0);
            });
        runner.withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder().privacy().build()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThatThrownBy(() -> context.getBean(GuardrailsChatClientFactory.class).builder(new EchoModel()))
                .hasMessageContaining("no PrivacyService");
        });
    }

    private static PrivacyService privacy() {
        return new PrivacyService(List.of((text, options, limits) -> {
            int start = text.indexOf("Alice");
            if (start < 0) return List.of();
            return List.of(new PiiSpan("PERSON", start, start + 5, 1.0));
        }), PiiAnalysisOptions.defaults());
    }

    private static String invoke(ChatClient chatClient, boolean streaming) {
        if (streaming) {
            return chatClient.prompt().user("Alice").stream().content().collectList()
                .map(parts -> String.join("", parts)).block(Duration.ofSeconds(10));
        }
        return chatClient.prompt().user("Alice").call().content();
    }

    private static final class EchoModel implements ChatModel {
        private Prompt lastPrompt;
        private final String response;

        private EchoModel() {
            this(null);
        }

        private EchoModel(String response) {
            this.response = response;
        }

        public ChatResponse call(Prompt prompt) {
            lastPrompt = prompt;
            String text = response;
            if (text == null) {
                text = prompt.getUserMessage().getText();
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        }
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }
    }
}
