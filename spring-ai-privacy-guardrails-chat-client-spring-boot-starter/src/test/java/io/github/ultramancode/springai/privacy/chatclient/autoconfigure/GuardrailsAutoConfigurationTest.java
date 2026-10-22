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
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionObserver;
import io.github.ultramancode.springai.privacy.security.SpringSecurityToolBoundary;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationChatClientFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyEnforcementObserver;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardrailsAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PrivacyGuardrailsAutoConfiguration.class,
            InspectionAutoConfiguration.class, GuardrailsAutoConfiguration.class));

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
            .withBean(PrivacyService.class, GuardrailsAutoConfigurationTest::privacyService)
            .withBean(ObservationRegistry.class, () -> observationRegistry)
            .withBean(ChatClientBuilderCustomizer.class, () -> chatClientBuilder -> chatClientBuilder.defaultSystem("Be concise"))
            .withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder()
                .privacy(GuardrailsPolicy.Privacy.builder()
                    .outputProtection()
                    .redact()
                    .build())
                .build())
            .run(context -> {
                assertThat(context).hasNotFailed();
                GuardrailsChatClientFactory guardrailsChatClientFactory = context.getBean(GuardrailsChatClientFactory.class);
                GuardrailsPolicy outputProtectionDisabledPolicy = context.getBean(GuardrailsPolicy.class)
                    .mutate()
                    .privacy(privacyPolicyBuilder -> privacyPolicyBuilder
                        .outputProtection()
                        .disable())
                    .build();
                assertThat(invoke(guardrailsChatClientFactory.builder(chatModel)
                    .build(), streaming))
                    .isEqualTo("[REDACTED_PERSON]");
                assertThat(invoke(guardrailsChatClientFactory.builder(chatModel, outputProtectionDisabledPolicy)
                    .build(), streaming))
                    .isEqualTo("Alice");
                assertThat(invoke(guardrailsChatClientFactory.builder(chatModel)
                    .build(), streaming))
                    .isEqualTo("[REDACTED_PERSON]");
                assertThat(chatModel.lastPrompt.getSystemMessage().getText()).isEqualTo("Be concise");
                assertThat(observations).hasValueGreaterThan(0);
            });
    }

    @Test
    @SuppressWarnings("removal")
    void appliesClientCustomizersBeforeBuilderCustomizers() {
        EchoModel chatModel = new EchoModel();
        List<String> customizerInvocations = new ArrayList<>();
        runner.withBean(PrivacyService.class, GuardrailsAutoConfigurationTest::privacyService)
            .withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder().privacy().build())
            .withBean(ChatClientCustomizer.class, () -> chatClientBuilder -> {
                customizerInvocations.add("client");
                chatClientBuilder.defaultSystem("Client system");
                chatClientBuilder.defaultUser("Default user");
            })
            .withBean(ChatClientBuilderCustomizer.class, () -> chatClientBuilder -> {
                customizerInvocations.add("builder");
                chatClientBuilder.defaultSystem("Builder system");
            })
            .run(context -> {
                assertThat(context).hasNotFailed();
                ChatClient chatClient = context.getBean(GuardrailsChatClientFactory.class)
                    .builder(chatModel)
                    .build();

                assertThat(customizerInvocations).containsExactly("client", "builder");
                assertThat(chatClient.prompt().call().content()).isEqualTo("Default user");
                assertThat(chatModel.lastPrompt.getSystemMessage().getText()).isEqualTo("Builder system");
            });
    }

    @Test
    void customizerCanEnableToolAdvisorAutoRegistration() {
        // Disable auto-registration by property, then re-enable it through the client customizer.
        runner.withPropertyValues("spring.ai.chat.client.tool-calling.enabled=false")
            .withBean(PrivacyService.class, GuardrailsAutoConfigurationTest::privacyService)
            .withBean(ChatClientBuilderCustomizer.class, () -> chatClientBuilder ->
                chatClientBuilder.defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(true)))
            .withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder()
                .privacy()
                .build())
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(callLookupTool(context.getBean(GuardrailsChatClientFactory.class))).isEqualTo("found");
            });
    }

    @Test
    void usesRegisteredManagerWhenToolAdvisorBuilderIsMissing() {
        CountingToolCallingManager registeredToolCallingManager = new CountingToolCallingManager();
        runner.withBean(PrivacyService.class, GuardrailsAutoConfigurationTest::privacyService)
            .withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder()
                .privacy()
                .build())
            .withBean(ToolCallingManager.class, () -> registeredToolCallingManager)
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(ToolCallingAdvisor.Builder.class);
                assertThat(callLookupTool(context.getBean(GuardrailsChatClientFactory.class))).isEqualTo("found");
                assertThat(registeredToolCallingManager.toolExecutions).hasValue(1);
            });
    }

    @Test
    void usesAdvisorBuilderManagerEvenWhenManagerBeansAreAmbiguous() {
        CountingToolCallingManager advisorToolCallingManager = new CountingToolCallingManager();
        CountingToolCallingManager firstToolCallingManager = new CountingToolCallingManager();
        CountingToolCallingManager secondToolCallingManager = new CountingToolCallingManager();
        runner.withBean(PrivacyService.class, GuardrailsAutoConfigurationTest::privacyService)
            .withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder()
                .privacy()
                .build())
            .withBean(ToolCallingAdvisor.Builder.class, () -> ToolCallingAdvisor.builder()
                .toolCallingManager(advisorToolCallingManager))
            .withBean("firstToolCallingManager", ToolCallingManager.class, () -> firstToolCallingManager)
            .withBean("secondToolCallingManager", ToolCallingManager.class, () -> secondToolCallingManager)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(callLookupTool(context.getBean(GuardrailsChatClientFactory.class))).isEqualTo("found");
                assertThat(advisorToolCallingManager.toolExecutions).hasValue(1);
                assertThat(firstToolCallingManager.toolExecutions).hasValue(0);
                assertThat(secondToolCallingManager.toolExecutions).hasValue(0);
            });
    }

    @Test
    void doesNotCreateDefaultPolicyFromPrivacyService() {
        runner.withBean(PrivacyService.class, GuardrailsAutoConfigurationTest::privacyService)
            .run(context -> {
                assertThat(context).hasNotFailed().doesNotHaveBean(GuardrailsPolicy.class);
                GuardrailsChatClientFactory guardrailsChatClientFactory = context.getBean(GuardrailsChatClientFactory.class);
                assertThatThrownBy(() -> guardrailsChatClientFactory.builder(new EchoModel()))
                    .hasMessageContaining("No default");
            });
    }

    @Test
    void allowsExplicitPolicyWhenDefaultPolicyBeansAreAmbiguous() {
        runner.withBean(PrivacyService.class, GuardrailsAutoConfigurationTest::privacyService)
            .withBean("first", GuardrailsPolicy.class, () -> GuardrailsPolicy.builder()
                .privacy()
                .build())
            .withBean("second", GuardrailsPolicy.class, () -> GuardrailsPolicy.builder()
                .privacy()
                .build())
            .run(context -> {
                assertThat(context).hasNotFailed();
                GuardrailsChatClientFactory guardrailsChatClientFactory = context.getBean(GuardrailsChatClientFactory.class);
                assertThatThrownBy(() -> guardrailsChatClientFactory.builder(new EchoModel())).hasMessageContaining("No default");
                GuardrailsPolicy explicitPolicy = GuardrailsPolicy.builder()
                    .privacy()
                    .build();
                ChatClient chatClient = guardrailsChatClientFactory.builder(new EchoModel(), explicitPolicy)
                    .build();
                assertThat(chatClient.prompt()
                    .user("Alice")
                    .call()
                    .content())
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
                    .finalOutputInspectionEnabled(true))
                .build())
            .run(context -> {
                assertThat(context).hasNotFailed();
                ChatClient chatClient = context.getBean(GuardrailsChatClientFactory.class)
                    .builder(new EchoModel())
                    .build();
                assertThat(invoke(chatClient, streaming)).isEqualTo("Alice");
                // One inspection for the model request and one for the final output.
                assertThat(inspections).hasValue(2);
            });
    }

    @Test
    void preservesStarterDefaultsWhenSelectingDirectionalServices() {
        List<String> inputInspectionTexts = new ArrayList<>();
        List<String> outputInspectionTexts = new ArrayList<>();
        AtomicInteger inspectionEventCount = new AtomicInteger();
        AtomicInteger privacyEventCount = new AtomicInteger();
        AtomicInteger completedObservationCount = new AtomicInteger();
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            public boolean supportsContext(Observation.Context context) { return true; }
            public void onStop(Observation.Context context) { completedObservationCount.incrementAndGet(); }
        });
        EchoModel chatModel = new EchoModel("Alice");
        InspectionService inputInspectionService = recordingInspectionService(inputInspectionTexts);
        InspectionService outputInspectionService = recordingInspectionService(outputInspectionTexts);
        runner.withBean("inputInspectionService", InspectionService.class, () -> inputInspectionService)
            .withBean("outputInspectionService", InspectionService.class, () -> outputInspectionService)
            .withBean(PrivacyService.class, GuardrailsAutoConfigurationTest::privacyService)
            .withBean(PrivacyEnforcementObserver.class, () -> event -> privacyEventCount.incrementAndGet())
            .withBean(InspectionObserver.class, () -> (report, execution) -> inspectionEventCount.incrementAndGet())
            .withBean(ObservationRegistry.class, () -> observationRegistry)
            .withBean(ChatClientBuilderCustomizer.class, () -> chatClientBuilder -> chatClientBuilder.defaultSystem("Be concise"))
            .withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder()
                .privacy(GuardrailsPolicy.Privacy.builder().outputProtection().redact().build())
                .inspection(GuardrailsPolicy.Inspection.builder().finalOutputInspectionEnabled(true).build()).build())
            .withUserConfiguration(DirectionalInspectionConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(GuardrailsChatClientFactory.class);
                ChatClient chatClient = context.getBean(GuardrailsChatClientFactory.class)
                    .builder(chatModel).build();

                assertThat(invoke(chatClient, false)).isEqualTo("[REDACTED_PERSON]");
                String protectedUserText = chatModel.lastPrompt.getUserMessage().getText();
                assertThat(protectedUserText).matches(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
                assertThat(inputInspectionTexts).containsExactly("Be concise", protectedUserText);
                assertThat(outputInspectionTexts).containsExactly("[REDACTED_PERSON]");
                assertThat(inspectionEventCount).hasValue(2);
                assertThat(privacyEventCount).hasValueGreaterThan(0);
                assertThat(completedObservationCount).hasValueGreaterThan(0);
            });
    }

    @Test
    void factoryBuildersDoNotShareInspectionOverrides() {
        List<String> primaryInspectionTexts = new ArrayList<>();
        List<String> customInspectionTexts = new ArrayList<>();
        InspectionService primaryInspectionService = recordingInspectionService(primaryInspectionTexts);
        InspectionService customInspectionService = recordingInspectionService(customInspectionTexts);
        runner.withBean("primaryInspectionService", InspectionService.class, () -> primaryInspectionService,
                definition -> definition.setPrimary(true))
            .withBean("otherInspectionService", InspectionService.class, () -> customInspectionService)
            .withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder().inspection().build())
            .run(context -> {
                assertThat(context).hasNotFailed();
                GuardrailsChatClientFactory.Builder customizedFactoryBuilder =
                    context.getBean(GuardrailsChatClientFactory.Builder.class);
                GuardrailsChatClientFactory.Builder defaultFactoryBuilder =
                    context.getBean(GuardrailsChatClientFactory.Builder.class);

                // Both builders start with primaryInspectionService.
                // Changing this builder must leave defaultFactoryBuilder unchanged.
                customizedFactoryBuilder.inputInspectionService(customInspectionService);

                ChatClient customizedChatClient = customizedFactoryBuilder.build()
                    .builder(new EchoModel()).build();
                ChatClient defaultChatClient = defaultFactoryBuilder.build()
                    .builder(new EchoModel()).build();
                invoke(customizedChatClient, false);
                invoke(defaultChatClient, false);

                assertThat(customInspectionTexts).containsExactly("Alice");
                assertThat(primaryInspectionTexts).containsExactly("Alice");
            });
    }

    @Test
    void requiresExplicitSelectionWhenInspectionServicesAreAmbiguous() {
        runner.withBean("firstInspectionService", InspectionService.class,
                () -> recordingInspectionService(new ArrayList<>()))
            .withBean("secondInspectionService", InspectionService.class,
                () -> recordingInspectionService(new ArrayList<>()))
            .run(context -> {
                assertThat(context).hasNotFailed();
                GuardrailsChatClientFactory guardrailsFactory = context.getBean(GuardrailsChatClientFactory.class);
                GuardrailsPolicy inputInspectionPolicy = GuardrailsPolicy.builder().inspection().build();
                assertThatThrownBy(() -> guardrailsFactory.builder(new EchoModel(), inputInspectionPolicy))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("no input InspectionService");
            });
    }

    @Configuration(proxyBeanMethods = false)
    static class DirectionalInspectionConfiguration {
        @Bean
        GuardrailsChatClientFactory guardrailsChatClientFactory(
            GuardrailsChatClientFactory.Builder guardrailsFactoryBuilder,
            @Qualifier("inputInspectionService") InspectionService inputInspectionService,
            @Qualifier("outputInspectionService") InspectionService outputInspectionService) {
            return guardrailsFactoryBuilder
                .inputInspectionService(inputInspectionService)
                .outputInspectionService(outputInspectionService)
                .build();
        }
    }

    private static InspectionService recordingInspectionService(List<String> inspectedTexts) {
        return new InspectionService(List.of(new ContentInspector() {
            public String inspectorId() { return "recorder"; }
            public boolean requiresPrivacyProcessedContent() { return false; }
            public InspectionResult inspect(InspectionRequest inspectionRequest) {
                inspectionRequest.segments().stream().map(ContentSegment::text).forEach(inspectedTexts::add);
                return InspectionResult.completed(inspectionRequest.segments().stream().map(ContentSegment::id)
                    .collect(Collectors.toSet()), List.of());
            }
        }));
    }

    @Test
    void connectsOptionalAuthorizationFactory() {
        AtomicInteger securityClientCustomizationCount = new AtomicInteger();
        SpringSecurityToolBoundary toolAuthorizationBoundary = SpringSecurityToolBoundary.builder(
                ToolCallingManager.builder().build(),
                (authentication, context) -> new AuthorizationDecision(true))
            .build();
        ToolAuthorizationChatClientFactory toolAuthorizationChatClientFactory = ToolAuthorizationChatClientFactory.builder()
            .toolAuthorizationBoundary(toolAuthorizationBoundary)
            .clientCustomizer(chatClientBuilder -> {
                securityClientCustomizationCount.incrementAndGet();
                return chatClientBuilder;
            })
            .build();
        EchoModel chatModel = new EchoModel();
        AtomicInteger guardrailsClientCustomizationCount = new AtomicInteger();
        runner.withBean(ToolAuthorizationChatClientFactory.class, () -> toolAuthorizationChatClientFactory)
            .withBean(ChatClientBuilderCustomizer.class, () -> chatClientBuilder -> {
                guardrailsClientCustomizationCount.incrementAndGet();
                chatClientBuilder.defaultSystem("Be concise");
            })
            .withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder()
                .toolAuthorization(true)
                .build())
            .run(context -> {
                assertThat(context).hasNotFailed();
                ChatClient chatClient = context.getBean(GuardrailsChatClientFactory.class)
                    .builder(chatModel)
                    .build();
                assertThat(chatClient.prompt()
                    .user("hello")
                    .call()
                    .content()).isEqualTo("hello");
                assertThat(chatModel.lastPrompt.getSystemMessage().getText()).isEqualTo("Be concise");
                // Only authorization configuration is reused from Security. Guardrails handles client customization.
                assertThat(guardrailsClientCustomizationCount).hasValue(1);
                assertThat(securityClientCustomizationCount).hasValue(0);
            });
    }

    @Test
    void failsToCreateClientBuilderWhenPrivacyServiceIsMissing() {
        runner.withBean(GuardrailsPolicy.class, () -> GuardrailsPolicy.builder()
                .privacy()
                .build())
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThatThrownBy(() -> context.getBean(GuardrailsChatClientFactory.class).builder(new EchoModel()))
                    .hasMessageContaining("no PrivacyService");
            });
    }

    private static String callLookupTool(GuardrailsChatClientFactory guardrailsChatClientFactory) {
        ChatModel chatModel = new ChatModel() {
            @Override
            public ToolCallingChatOptions getOptions() {
                return ToolCallingChatOptions.builder()
                    .build();
            }

            @Override
            public ChatResponse call(Prompt prompt) {
                AssistantMessage toolCall = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}")))
                    .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }
        };
        ToolCallback lookupTool = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                    .name("lookup")
                    .description("Look up a record")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder()
                    .returnDirect(true)
                    .build();
            }

            @Override
            public String call(String input) {
                return "found";
            }
        };
        return guardrailsChatClientFactory.builder(chatModel)
            .defaultTools(lookupTool)
            .build()
            .prompt()
            .user("Find the record")
            .call()
            .content();
    }

    private static final class CountingToolCallingManager implements ToolCallingManager {
        private final ToolCallingManager delegate = ToolCallingManager.builder()
            .build();
        private final AtomicInteger toolExecutions = new AtomicInteger();

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return delegate.resolveToolDefinitions(chatOptions);
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            toolExecutions.incrementAndGet();
            return delegate.executeToolCalls(prompt, chatResponse);
        }
    }

    private static PrivacyService privacyService() {
        return new PrivacyService(List.of((text, options, limits) -> {
            int start = text.indexOf("Alice");
            if (start < 0) {
                return List.of();
            }
            return List.of(new PiiSpan("PERSON", start, start + 5, 1.0));
        }), PiiAnalysisOptions.defaults());
    }

    private static String invoke(ChatClient chatClient, boolean streaming) {
        if (streaming) {
            return chatClient.prompt()
                .user("Alice")
                .stream()
                .content()
                .collectList()
                .map(parts -> String.join("", parts))
                .block(Duration.ofSeconds(10));
        }
        return chatClient.prompt()
            .user("Alice")
            .call()
            .content();
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
