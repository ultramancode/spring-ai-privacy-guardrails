package io.github.ultramancode.springai.privacy.autoconfigure;

import io.github.ultramancode.springai.privacy.core.EntityTypeRegistry;
import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailureObserver;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailure;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailurePolicy;
import io.github.ultramancode.springai.privacy.core.PiiResolutionMode;
import io.github.ultramancode.springai.privacy.core.PiiResolutionPolicy;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.RegexPiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.RegexPiiRule;
import io.github.ultramancode.springai.privacy.core.ResolvedPiiSpan;
import io.github.ultramancode.springai.privacy.springai.PrivacyInputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyLifecycleAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyModelBoundaryAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolContextAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallValidationAdvisor;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.PatternSyntaxException;

import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PrivacyGuardrailsAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PrivacyGuardrailsAutoConfiguration.class))
            .withPropertyValues("spring.ai.privacy.enabled=true");

    private final ApplicationContextRunner bootChatClientContextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ChatClientAutoConfiguration.class,
                    PrivacyGuardrailsAutoConfiguration.class
            ))
            .withPropertyValues("spring.ai.privacy.enabled=true");

    @Test
    void configurationMetadataPublishesTheCanonicalPropertySurface() throws Exception {
        try (InputStream input = PrivacyGuardrailsAutoConfigurationTest.class
                .getResourceAsStream("/META-INF/spring-configuration-metadata.json")) {
            assertThat(input).isNotNull();
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(metadata)
                    .contains("spring.ai.privacy.analysis.included-entity-types")
                    .contains("spring.ai.privacy.tools.disclosures")
                    .contains("spring.ai.privacy.response-inspection.max-stream-frames")
                    .contains("spring.ai.privacy.response-inspection.max-characters")
                    .contains("spring.ai.privacy.response-inspection.max-media-bytes")
                    .contains("spring.ai.privacy.response-inspection.stream-idle-timeout");
        }
    }

    @Test
    void autoConfigurationIsInactiveUntilGloballyEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PrivacyGuardrailsAutoConfiguration.class))
                .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of())
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PrivacyService.class)
                        .doesNotHaveBean(PrivacyToolCallbackFactory.class)
                        .doesNotHaveBean(PrivacyChatClientConfigurer.class));

        assertThat(new PrivacyGuardrailsProperties().isEnabled()).isFalse();
    }

    @Test
    void autoConfigurationCreatesCoreServiceAndExplicitConfigurerWithoutPublicAdvisorBeans() {
        this.contextRunner
                .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of())
                .run(context -> assertThat(context)
                        .hasSingleBean(PrivacyService.class)
                        .hasSingleBean(PrivacyToolCallbackFactory.class)
                        .hasSingleBean(PrivacyChatClientConfigurer.class)
                        .doesNotHaveBean(PrivacyLifecycleAdvisor.class)
                        .doesNotHaveBean(PrivacyInputAdvisor.class)
                        .doesNotHaveBean(PrivacyModelBoundaryAdvisor.class)
                        .doesNotHaveBean(PrivacyToolContextAdvisor.class)
                        .doesNotHaveBean(PrivacyToolCallValidationAdvisor.class)
                        .doesNotHaveBean(EntityTypeRegistry.class)
                        .doesNotHaveBean(PrivacyOutputAdvisor.class)
                        .doesNotHaveBean(RegexPiiAnalyzer.class));
    }

    @Test
    void autoConfigurationFailsClosedWhenNoAnalyzerIsConfigured() {
        this.contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage(
                            "No PiiAnalyzer is configured. Enable regex, OpenNLP, Presidio, or provide a custom analyzer"
                    );
        });
    }

    @Test
    void explicitConfigurerIgnoresUnrelatedUserAdvisorBeans() {
        PrivacyService service = new PrivacyService(
                List.of((text, options) -> List.of()),
                PiiAnalysisOptions.defaults()
        );
        PrivacyToolContextAdvisor weakToolContext = new PrivacyToolContextAdvisor(service);
        PrivacyModelBoundaryAdvisor weakModelBoundary = new PrivacyModelBoundaryAdvisor(service);

        this.contextRunner
                .withBean(PrivacyService.class, () -> service)
                .withBean(PrivacyToolContextAdvisor.class, () -> weakToolContext)
                .withBean(PrivacyModelBoundaryAdvisor.class, () -> weakModelBoundary)
                .run(context -> {
                    ChatClient.Builder builder = mock(ChatClient.Builder.class);
                    context.getBean(PrivacyChatClientConfigurer.class).configure(builder);
                    @SuppressWarnings("unchecked")
                    ArgumentCaptor<List<Advisor>> advisors = ArgumentCaptor.forClass(List.class);
                    verify(builder).defaultAdvisors(advisors.capture());

                    assertThat(advisors.getValue())
                            .filteredOn(PrivacyToolContextAdvisor.class::isInstance)
                            .singleElement()
                            .isNotSameAs(weakToolContext);
                    assertThat(advisors.getValue())
                            .filteredOn(PrivacyModelBoundaryAdvisor.class::isInstance)
                            .singleElement()
                            .isNotSameAs(weakModelBoundary);
                });
    }

    @Test
    void autoConfigurationFailsFastWhenRegexIsEnabledWithoutRules() {
        this.contextRunner
                .withPropertyValues("spring.ai.privacy.regex.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "spring.ai.privacy.regex.rules must contain at least one rule when regex is enabled"
                            );
                });
    }

    @Test
    void regexAnalyzerRejectsNullRulesAtItsOnlyConfigurationBoundary() {
        PrivacyGuardrailsProperties properties = new PrivacyGuardrailsProperties();
        properties.getRegex().setEnabled(true);
        properties.getRegex().getRules().add(null);
        PrivacyGuardrailsAutoConfiguration autoConfiguration = new PrivacyGuardrailsAutoConfiguration();

        assertThatThrownBy(() -> autoConfiguration.regexPiiAnalyzer(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("spring.ai.privacy.regex.rules[0] must not be null");
    }

    @Test
    void configurationCollectionsRejectNullInsteadOfDeferringAmbiguousFailures() {
        PrivacyGuardrailsProperties properties = new PrivacyGuardrailsProperties();

        assertThatThrownBy(() -> properties.getAnalysis().setIncludedEntityTypes(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("analysis.included-entity-types must not be null");
        assertThatThrownBy(() -> properties.getRegex().setRules(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("regex.rules must not be null");
        assertThatThrownBy(() -> properties.getTools().setDisclosures(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("tools.disclosures must not be null");
    }

    @Test
    void responseInspectionLimitsArePositiveAndHaveOneValidatedConfigurationSource() {
        PrivacyGuardrailsProperties.ResponseInspection inspection =
                new PrivacyGuardrailsProperties().getResponseInspection();
        inspection.setMaxStreamFrames(7);
        inspection.setMaxCharacters(8);
        inspection.setMaxMediaBytes(9);
        inspection.setStreamIdleTimeout(Duration.ofSeconds(10));

        assertThat(inspection.limits())
                .extracting(
                        "maxStreamFrames",
                        "maxCharacters",
                        "maxMediaBytes",
                        "streamIdleTimeout"
                )
                .containsExactly(7, 8L, 9L, Duration.ofSeconds(10));
        assertThatThrownBy(() -> inspection.setMaxStreamFrames(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("response-inspection.max-stream-frames must be positive");
        assertThatThrownBy(() -> inspection.setMaxCharacters(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("response-inspection.max-characters must be positive");
        assertThatThrownBy(() -> inspection.setMaxMediaBytes(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("response-inspection.max-media-bytes must be positive");
        assertThatThrownBy(() -> inspection.setStreamIdleTimeout(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("response-inspection.stream-idle-timeout must be positive");
    }

    @Test
    void responseInspectionPropertiesBindIndependentlyOfTheOptionalOutputPolicy() {
        this.contextRunner
                .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of())
                .withPropertyValues(
                        "spring.ai.privacy.output.enabled=false",
                        "spring.ai.privacy.response-inspection.max-stream-frames=7",
                        "spring.ai.privacy.response-inspection.max-characters=8",
                        "spring.ai.privacy.response-inspection.max-media-bytes=9",
                        "spring.ai.privacy.response-inspection.stream-idle-timeout=10s"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PrivacyGuardrailsProperties properties =
                            context.getBean(PrivacyGuardrailsProperties.class);
                    assertThat(properties.getOutput().isEnabled()).isFalse();
                    assertThat(properties.getResponseInspection().limits())
                            .extracting(
                                    "maxStreamFrames",
                                    "maxCharacters",
                                    "maxMediaBytes",
                                    "streamIdleTimeout"
                            )
                            .containsExactly(7, 8L, 9L, Duration.ofSeconds(10));
                });
    }

    @Test
    void configuredEntityAliasesFeedTheServiceWithoutExposingABaseRegistryBean() {
        this.contextRunner
                .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of(
                        new PiiSpan("PER", 0, text.length(), 0.95)
                ))
                .withPropertyValues("spring.ai.privacy.analysis.entity-aliases[PER]=CUSTOMER_ID")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EntityTypeRegistry.class);
                    assertThat(context.getBean(PrivacyService.class).analyze("Alice"))
                            .singleElement()
                            .extracting(ResolvedPiiSpan::entityType)
                            .isEqualTo("CUSTOMER_ID");
                });
    }

    @Test
    void configuredEntityAliasesRejectNonCanonicalTypesAtStartup() {
        this.contextRunner
                .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of())
                .withPropertyValues("spring.ai.privacy.analysis.entity-aliases[PER]=customer-id")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "entityType must use uppercase ASCII letters and digits "
                                    + "separated by single underscores"
                    );
                });
    }

    @Test
    void applicationProvidedEntityRegistryRemainsTheExplicitOverridePath() {
        this.contextRunner
                .withBean(EntityTypeRegistry.class, () -> new EntityTypeRegistry(
                        Map.of("PER", "CUSTOMER_ID")
                ))
                .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of(
                        new PiiSpan("PER", 0, text.length(), 0.95)
                ))
                .run(context -> {
                    assertThat(context).hasSingleBean(EntityTypeRegistry.class);
                    assertThat(context.getBean(PrivacyService.class).analyze("Alice"))
                            .singleElement()
                            .extracting(ResolvedPiiSpan::entityType)
                            .isEqualTo("CUSTOMER_ID");
                });
    }

    @Test
    void toolDisclosuresRejectAmbiguousConfiguration() {
        assertThatThrownBy(() -> ToolDisclosurePolicy.byToolName(Map.of(
                " customerLookup",
                Set.of("CUSTOMER_ID")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("tool disclosure names must not contain whitespace");
        assertThatThrownBy(() -> ToolDisclosurePolicy.byToolName(Map.of(
                "customerLookup",
                List.of("customer-id")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
    }

    @Test
    void invalidRegexPreservesApplicationOwnedCompilationFailure() {
        PrivacyGuardrailsProperties properties = new PrivacyGuardrailsProperties();
        PrivacyGuardrailsProperties.Regex.Rule rule = new PrivacyGuardrailsProperties.Regex.Rule();
        rule.setEntityType("CUSTOMER_ID");
        rule.setPattern("(?<secret>CUST-[");
        properties.getRegex().setRules(List.of(rule));

        assertThatThrownBy(() -> new PrivacyGuardrailsAutoConfiguration().regexPiiAnalyzer(properties))
                .isInstanceOf(PatternSyntaxException.class)
                .hasMessageContaining("CUST");
    }

    @Test
    void autoConfigurationGlobalSwitchDisablesEveryPrivacyBoundary() {
        this.contextRunner
                .withPropertyValues("spring.ai.privacy.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PrivacyService.class)
                        .doesNotHaveBean(PrivacyLifecycleAdvisor.class)
                        .doesNotHaveBean(PrivacyInputAdvisor.class)
                        .doesNotHaveBean(PrivacyModelBoundaryAdvisor.class)
                        .doesNotHaveBean(PrivacyToolContextAdvisor.class)
                        .doesNotHaveBean(PrivacyToolCallValidationAdvisor.class)
                        .doesNotHaveBean(PrivacyOutputAdvisor.class)
                        .doesNotHaveBean(PrivacyToolCallbackFactory.class)
                        .doesNotHaveBean(PrivacyChatClientConfigurer.class));
    }

    @Test
    void explicitConfigurerRegistersTheCompleteEnabledAdvisorBundle() {
        this.contextRunner
                .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of())
                .withPropertyValues("spring.ai.privacy.output.enabled=true")
                .run(context -> {
                    ChatClient.Builder builder = mock(ChatClient.Builder.class);
                    PrivacyChatClientConfigurer configurer = context.getBean(PrivacyChatClientConfigurer.class);
                    assertThat(configurer.configure(builder)).isSameAs(builder);

                    @SuppressWarnings("unchecked")
                    ArgumentCaptor<List<Advisor>> advisors = ArgumentCaptor.forClass(List.class);
                    verify(builder).defaultAdvisors(advisors.capture());
                    assertThat(context).doesNotHaveBean(PrivacyOutputAdvisor.class);
                    assertThat(advisors.getValue().stream()
                            .map(Advisor::getClass)
                            .toList())
                            .isEqualTo(List.of(
                                    PrivacyLifecycleAdvisor.class,
                                    PrivacyInputAdvisor.class,
                                    PrivacyOutputAdvisor.class,
                                    PrivacyToolContextAdvisor.class,
                                    PrivacyToolCallValidationAdvisor.class,
                                    PrivacyModelBoundaryAdvisor.class
                            ));
                });
    }

    @Test
    void explicitConfigurerRejectsApplyingTheBoundaryTwiceToTheSameBuilder() {
        this.contextRunner
                .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of())
                .run(context -> {
                    ChatClient.Builder builder = mock(ChatClient.Builder.class);
                    PrivacyChatClientConfigurer configurer = context.getBean(PrivacyChatClientConfigurer.class);

                    configurer.configure(builder);

                    assertThatThrownBy(() -> configurer.configure(builder))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessage(
                                    "PrivacyChatClientConfigurer cannot configure the same ChatClient.Builder more than once"
                            );
                });
    }

    @Test
    void bootManagedPrototypeBuildersRemainUnprotectedByDefaultUntilExplicitlySelected() {
        CapturingChatModel model = new CapturingChatModel();
        this.bootChatClientContextRunner
                .withBean(ChatModel.class, () -> model)
                .withBean(PiiAnalyzer.class, PrivacyGuardrailsAutoConfigurationTest::emailAnalyzer)
                .run(context -> {
                    ChatClient.Builder firstBuilder = context.getBean(ChatClient.Builder.class);
                    ChatClient.Builder secondBuilder = context.getBean(ChatClient.Builder.class);

                    assertThat(firstBuilder).isNotSameAs(secondBuilder);
                    firstBuilder.build().prompt().user("first@example.test").call().content();

                    assertThat(model.prompts())
                            .singleElement()
                            .asString()
                            .contains("first@example.test");
                    assertThat(context.getBean(PrivacyService.class).activeSessionCount()).isZero();
                });
    }

    @Test
    void clonedAndMutatedProtectedClientsKeepTheCompleteBoundaryWithoutReconfiguration() {
        CapturingChatModel model = new CapturingChatModel();
        this.contextRunner
                .withBean(PiiAnalyzer.class, PrivacyGuardrailsAutoConfigurationTest::emailAnalyzer)
                .run(context -> {
                    PrivacyChatClientConfigurer privacyConfigurer =
                            context.getBean(PrivacyChatClientConfigurer.class);
                    ChatClient.Builder protectedBuilder =
                            privacyConfigurer.configure(ChatClient.builder(model));

                    ChatClient clonedClient = protectedBuilder.clone().build();
                    ChatClient mutatedClient = protectedBuilder.build().mutate().build();
                    clonedClient.prompt().user("clone@example.test").call().content();
                    mutatedClient.prompt().user("mutate@example.test").call().content();

                    assertThat(model.prompts())
                            .hasSize(2)
                            .allSatisfy(prompt -> assertThat(prompt)
                                    .doesNotContain("clone@example.test", "mutate@example.test")
                                    .containsPattern(OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS")));
                    assertThat(context.getBean(PrivacyService.class).activeSessionCount()).isZero();
                });
    }

    @Test
    void reconfiguringAMutatedProtectedClientFailsClosedBeforeModelInvocation() {
        CapturingChatModel model = new CapturingChatModel();
        this.contextRunner
                .withBean(PiiAnalyzer.class, PrivacyGuardrailsAutoConfigurationTest::emailAnalyzer)
                .run(context -> {
                    PrivacyChatClientConfigurer privacyConfigurer =
                            context.getBean(PrivacyChatClientConfigurer.class);
                    ChatClient protectedClient =
                            privacyConfigurer.configure(ChatClient.builder(model)).build();
                    ChatClient.Builder copiedBoundary = protectedClient.mutate();

                    privacyConfigurer.configure(copiedBoundary);

                    assertThatThrownBy(() -> copiedBoundary.build()
                            .prompt()
                            .user("duplicate@example.test")
                            .call()
                            .content())
                            .isInstanceOf(PrivacyGuardrailException.class)
                            .hasMessage(
                                    "PrivacyLifecycleAdvisor requires exactly one complete mandatory privacy advisor set"
                            );
                    assertThat(model.prompts()).isEmpty();
                    assertThat(context.getBean(PrivacyService.class).activeSessionCount()).isZero();
                });
    }

    @Test
    void explicitConfigurerRunsCompleteToolLoopOutputPolicyAndSessionCleanup() {
        AtomicReference<String> delegateToolInput = new AtomicReference<>();
        this.contextRunner
                .withBean(RegexPiiAnalyzer.class, () -> new RegexPiiAnalyzer(List.of(
                        new RegexPiiRule("CUSTOMER_ID", "\\bCUST-\\d{4}\\b", 0.99, 0),
                        new RegexPiiRule(
                                "EMAIL_ADDRESS",
                                "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
                                0.95,
                                0
                        )
                )))
                .withPropertyValues(
                        "spring.ai.privacy.output.enabled=true",
                        "spring.ai.privacy.tools.disclosures[customerLookup][0]=CUSTOMER_ID"
                )
                .run(context -> {
                    StarterToolLoopModel model = new StarterToolLoopModel();
                    ChatClient.Builder builder = ChatClient.builder(model);
                    context.getBean(PrivacyChatClientConfigurer.class).configure(builder);
                    ToolCallbackProvider sourceProvider = () -> new ToolCallback[]{
                            customerLookup(delegateToolInput)
                    };
                    ToolCallbackProvider protectedTools = context.getBean(PrivacyToolCallbackFactory.class)
                            .wrapProvider(sourceProvider);
                    ChatClient client = builder.defaultTools(protectedTools).build();

                    String result = client.prompt()
                            .user("Find CUST-0042 for user@example.test")
                            .call()
                            .content();

                    assertThat(model.calls()).isEqualTo(2);
                    assertThat(model.firstPrompt())
                            .doesNotContain("CUST-0042", "user@example.test")
                            .containsPattern(OpaquePiiTokenFormat.patternForEntityType("CUSTOMER_ID"))
                            .containsPattern(OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS"));
                    assertThat(delegateToolInput.get())
                            .contains("CUST-0042")
                            .doesNotContain("user@example.test")
                            .containsPattern(OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS"));
                    assertThat(model.secondToolResult())
                            .doesNotContain("CUST-9000", "result@example.test")
                            .containsPattern(OpaquePiiTokenFormat.patternForEntityType("CUSTOMER_ID"))
                            .containsPattern(OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS"));
                    assertThat(result)
                            .doesNotContain("final@example.test")
                            .containsPattern(OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS"));
                    assertThat(context.getBean(PrivacyService.class).activeSessionCount()).isZero();
                });
    }

    @Test
    void explicitlyConfiguredBoundaryRejectsWrappersFromAnotherFactoryEvenWithTheSameService() {
        this.contextRunner
                .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of())
                .run(context -> {
                    PrivacyService service = context.getBean(PrivacyService.class);
                    PrivacyToolCallbackFactory foreignFactory = new PrivacyToolCallbackFactory(
                            service,
                            ToolDisclosurePolicy.denyAll()
                    );
                    StarterToolLoopModel model = new StarterToolLoopModel();
                    ChatClient.Builder builder = ChatClient.builder(model);
                    context.getBean(PrivacyChatClientConfigurer.class).configure(builder);
                    ChatClient client = builder
                            .defaultTools(foreignFactory.wrap(customerLookup(new AtomicReference<>())))
                            .build();

                    assertThatThrownBy(() -> client.prompt().user("hello").call().content())
                            .isInstanceOf(PrivacyGuardrailException.class)
                            .hasMessage(
                                    "PrivacyToolContextAdvisor rejected a tool callback from another privacy factory"
                            );
                    assertThat(model.calls()).isZero();
                    assertThat(service.activeSessionCount()).isZero();
                });
    }

    @Test
    void autoConfigurationCreatesRegexAnalyzerWhenRulesAreEnabled() {
        this.contextRunner
                .withPropertyValues(
                        "spring.ai.privacy.regex.enabled=true",
                        "spring.ai.privacy.regex.rules[0].entity-type=EMPLOYEE_ID",
                        "spring.ai.privacy.regex.rules[0].pattern=\\bEMP-\\d{4}\\b",
                        "spring.ai.privacy.regex.rules[0].score=0.91"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(RegexPiiAnalyzer.class);

                    PrivacyService privacyService = context.getBean(PrivacyService.class);
                    try (var session = privacyService.openSession()) {
                        assertThat(privacyService.tokenize(session.handle(), "Owner EMP-1234"))
                                .matches("Owner "
                                        + OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID").pattern());
                    }
                });
    }

    @Test
    void autoConfigurationWiresSanitizedPartialFailureObserverIntoRuntimeAnalysis() {
        AtomicReference<PiiAnalyzerFailure> observed = new AtomicReference<>();
        this.contextRunner
                .withBean("brokenAnalyzer", PiiAnalyzer.class, () -> namedAnalyzer(
                        "BROKEN", (text, options) -> {
                            throw new IllegalStateException("must-not-cross-boundary-" + text);
                        }
                ))
                .withBean("workingAnalyzer", PiiAnalyzer.class, () -> namedAnalyzer(
                        "WORKING", (text, options) -> List.of()
                ))
                .withBean(PiiAnalyzerFailureObserver.class, () -> observed::set)
                .withPropertyValues("spring.ai.privacy.analysis.failure-policy=allow-partial")
                .run(context -> {
                    context.getBean(PrivacyService.class).analyze("Alice");

                    assertThat(observed.get()).isNotNull();
                    assertThat(observed.get().provider()).isEqualTo("BROKEN");
                    assertThat(observed.get().toString()).doesNotContain("Alice");
                });
    }

    @Test
    void customRegexAnalyzerIsTheSingleSourceForItsTrustedEntityTypes() {
        this.contextRunner
                .withBean(RegexPiiAnalyzer.class, () -> new RegexPiiAnalyzer(List.of(
                        new RegexPiiRule("CUSTOMER_ID", "\\bCUST-\\d{4}\\b", 0.95, 0)
                )))
                .withPropertyValues("spring.ai.privacy.regex.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(RegexPiiAnalyzer.class);
                    assertThat(context).doesNotHaveBean(EntityTypeRegistry.class);

                    PrivacyService privacyService = context.getBean(PrivacyService.class);
                    try (var session = privacyService.openSession()) {
                        assertThat(privacyService.tokenize(session.handle(), "Owner CUST-1234"))
                                .matches("Owner "
                                        + OpaquePiiTokenFormat.patternForEntityType("CUSTOMER_ID").pattern());
                    }
                });
    }

    @Test
    void autoConfigurationBindsResolutionAndToolDisclosurePolicies() {
        this.contextRunner
                .withBean(PiiAnalyzer.class, () -> new PiiAnalyzer() {
                    @Override
                    public List<PiiSpan> analyze(
                            String text,
                            PiiAnalysisOptions options
                    ) {
                        return List.of();
                    }

                    @Override
                    public String providerId() {
                        return "PRESIDIO";
                    }
                })
                .withPropertyValues(
                        "spring.ai.privacy.analysis.mode=primary",
                        "spring.ai.privacy.analysis.primary-provider=presidio",
                        "spring.ai.privacy.analysis.failure-policy=require-primary",
                        "spring.ai.privacy.analysis.provider-minimum-scores.presidio=0.72",
                        "spring.ai.privacy.tools.disclosures[customerLookup][0]=CUSTOMER_ID"
                )
                .run(context -> {
                    PiiResolutionPolicy policy = context.getBean(PiiResolutionPolicy.class);
                    assertThat(policy.mode()).isEqualTo(PiiResolutionMode.PRIMARY);
                    assertThat(policy.primaryProvider()).isEqualTo("PRESIDIO");
                    assertThat(policy.failurePolicy()).isEqualTo(PiiAnalyzerFailurePolicy.REQUIRE_PRIMARY);
                    assertThat(policy.providerMinimumScores()).containsEntry("PRESIDIO", 0.72);

                    ToolDisclosurePolicy toolPolicy = context.getBean(ToolDisclosurePolicy.class);
                    assertThat(toolPolicy.scopeFor(tool("customerLookup")).entityTypes())
                            .containsExactly("CUSTOMER_ID");
                    assertThat(toolPolicy.scopeFor(tool("externalSearch")).entityTypes())
                            .isEmpty();
                });
    }

    private ToolDefinition tool(String name) {
        return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
    }

    private static PiiAnalyzer emailAnalyzer() {
        return new RegexPiiAnalyzer(List.of(new RegexPiiRule(
                "EMAIL_ADDRESS",
                "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
                0.95,
                0
        )));
    }

    private static final class CapturingChatModel implements ChatModel {

        private final List<String> prompts = new CopyOnWriteArrayList<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompts.add(prompt.getInstructions().stream()
                    .map(Message::getText)
                    .filter(Objects::nonNull)
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse(""));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        List<String> prompts() {
            return List.copyOf(this.prompts);
        }
    }

    private ToolCallback customerLookup(AtomicReference<String> delegateToolInput) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("customerLookup")
                        .description("Returns one synthetic customer")
                        .inputSchema("{\"type\":\"object\"}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                delegateToolInput.set(toolInput);
                return "owner CUST-9000 result@example.test";
            }
        };
    }

    private static final class StarterToolLoopModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile String firstPrompt;
        private volatile String secondToolResult;

        @Override
        public ChatResponse call(Prompt prompt) {
            int invocation = this.calls.incrementAndGet();
            if (invocation == 1) {
                this.firstPrompt = prompt.getInstructions().stream()
                        .map(Message::getText)
                        .filter(Objects::nonNull)
                        .reduce((left, right) -> left + "\n" + right)
                        .orElse("");
                String customerToken = token(this.firstPrompt, "CUSTOMER_ID");
                String emailToken = token(this.firstPrompt, "EMAIL_ADDRESS");
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "customerLookup",
                                "{\"customerId\":\"" + customerToken
                                        + "\",\"email\":\"" + emailToken + "\"}"
                        )))
                        .build();
                return new ChatResponse(List.of(new Generation(toolCall)));
            }
            this.secondToolResult = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .findFirst()
                    .orElseThrow();
            return new ChatResponse(List.of(new Generation(
                    new AssistantMessage("Contact final@example.test")
            )));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private static String token(String text, String entityType) {
            Matcher matcher = OpaquePiiTokenFormat.patternForEntityType(entityType).matcher(text);
            if (!matcher.find()) {
                throw new IllegalStateException("Expected protected " + entityType + " token");
            }
            return matcher.group();
        }

        int calls() {
            return this.calls.get();
        }

        String firstPrompt() {
            return this.firstPrompt;
        }

        String secondToolResult() {
            return this.secondToolResult;
        }
    }

    private PiiAnalyzer namedAnalyzer(String providerId, PiiAnalyzer delegate) {
        return new PiiAnalyzer() {
            @Override
            public List<io.github.ultramancode.springai.privacy.core.PiiSpan> analyze(
                    String text,
                    PiiAnalysisOptions options
            ) {
                return delegate.analyze(text, options);
            }

            @Override
            public String providerId() {
                return providerId;
            }
        };
    }
}
