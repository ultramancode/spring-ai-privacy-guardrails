package io.github.ultramancode.springai.privacy.inspection.autoconfigure;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionDecision;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionChatClientConfigurer;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionBlockedException;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(InspectionAutoConfiguration.class));

    private ContentInspector rules() {
        return new RuleBasedContentInspector("application-rules", List.of(InspectionRule.literal("attack", InspectionFinding.Category.PROMPT_INJECTION, "attack")));
    }

    @Test
    void configurationMetadataDescribesAllInspectionProperties() throws Exception {
        try (InputStream resource =
                InspectionProperties.class.getResourceAsStream(
                        "/META-INF/spring-configuration-metadata.json")) {
            assertThat(resource).isNotNull();
            Map<String, Object> metadata = JsonParserFactory.getJsonParser().parseMap(
                    new String(resource.readAllBytes(), StandardCharsets.UTF_8));
            List<?> properties = (List<?>) metadata.get("properties");
            assertThat(properties)
                    .extracting(property -> (String) ((Map<?, ?>) property).get("name"))
                    .containsExactlyInAnyOrder(
                            "spring.ai.inspection.enabled",
                            "spring.ai.inspection.max-segments",
                            "spring.ai.inspection.max-characters",
                            "spring.ai.inspection.timeout",
                            "spring.ai.inspection.failure-policy",
                            "spring.ai.inspection.failure-policy-overrides",
                            "spring.ai.inspection.output.enabled",
                            "spring.ai.inspection.output.max-frames",
                            "spring.ai.inspection.output.stream-timeout");
            for (Object property : properties) {
                Object description = ((Map<?, ?>) property).get("description");
                assertThat(description).isInstanceOf(String.class);
                assertThat((String) description).isNotBlank();
            }
        }
    }

    @Test
    void disabledByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(InspectionService.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputInspectionRequiresSeparateOptIn(boolean enabled) {
        var model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("attack"))));
            }
        };
        runner.withPropertyValues("spring.ai.inspection.enabled=true", "spring.ai.inspection.output.enabled=" + enabled)
                .withBean(ContentInspector.class, this::rules).run(context -> {
                    assertThat(context).hasNotFailed();
                    var client = context.getBean(InspectionChatClientConfigurer.class).configure(ChatClient.builder(model)).build();
                    if (enabled) {
                        assertThatThrownBy(() -> client.prompt().user("hello").call().content())
                                .isInstanceOf(InspectionBlockedException.class);
                    } else {
                        assertThat(client.prompt().user("hello").call().content()).isEqualTo("attack");
                    }
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"max-frames=0", "stream-timeout=0s", "stream-timeout=-1s"})
    void invalidEnabledOutputBudgetsFailStartup(String setting) {
        runner.withPropertyValues("spring.ai.inspection.enabled=true", "spring.ai.inspection.output.enabled=true",
                        "spring.ai.inspection.output." + setting)
                .withBean(ContentInspector.class, this::rules).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enablingWithoutInspectorsFailsStartup() {
        runner.withPropertyValues("spring.ai.inspection.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @ParameterizedTest
    @CsvSource({"FAIL_CLOSED, FAIL_OPEN, ALLOW", "FAIL_OPEN, FAIL_CLOSED, BLOCK"})
    void inspectorFailurePolicyOverridesBindByExactId(
            InspectionFailurePolicy commonPolicy,
            InspectionFailurePolicy overridePolicy,
            InspectionDecision expectedDecision) {
        String inspectorId = "Optional.Guard_v1";
        ContentInspector failingInspector = new ContentInspector() {
            @Override
            public String inspectorId() {
                return inspectorId;
            }

            @Override
            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            @Override
            public InspectionResult inspect(InspectionRequest request) {
                return InspectionResult.failed(InspectionFailureCode.TIMEOUT);
            }
        };
        runner.withPropertyValues(
                        "spring.ai.inspection.enabled=true",
                        "spring.ai.inspection.failure-policy=" + commonPolicy,
                        "spring.ai.inspection.failure-policy-overrides[" + inspectorId + "]=" + overridePolicy)
                .withBean(ContentInspector.class, () -> failingInspector)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(InspectionProperties.class).getFailurePolicyOverrides())
                            .containsEntry(inspectorId, overridePolicy);
                    InspectionRequest request = new InspectionRequest(
                            List.of(new ContentSegment("s1", ContentSegment.Role.USER,
                                    ContentSegment.PrivacyProcessingStatus.UNPROCESSED, "hello")),
                            context.getBean(InspectionProperties.class).limits());

                    InspectionReport report = context.getBean(InspectionService.class).inspect(request);

                    assertThat(report.decision()).isEqualTo(expectedDecision);
                    assertThat(report.outcomes().get(0).result().failureCode())
                            .isEqualTo(InspectionFailureCode.TIMEOUT);
                });
    }

    @Test
    void unknownInspectorFailurePolicyOverrideFailsStartup() {
        runner.withPropertyValues(
                        "spring.ai.inspection.enabled=true",
                        "spring.ai.inspection.failure-policy-overrides[unknown]=FAIL_OPEN")
                .withBean(ContentInspector.class, this::rules)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("Failure policy overrides must reference configured inspector IDs");
                });
    }

    @Test
    void runsWithoutPrivacyOrModelBackendDependencies() {
        runner.withClassLoader(
                        new FilteredClassLoader(
                                "io.github.ultramancode.springai.privacy.springai",
                                "io.github.ultramancode.springai.privacy.inspection.openaicompatible"))
                .withPropertyValues("spring.ai.inspection.enabled=true")
                .withBean(ContentInspector.class, this::rules)
                .run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .hasSingleBean(InspectionChatClientConfigurer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"max-segments=0", "max-characters=0", "timeout=0s"})
    void invalidLimitsFailStartup(String property) {
        runner.withPropertyValues(
                        "spring.ai.inspection.enabled=true", "spring.ai.inspection." + property)
                .withBean(ContentInspector.class, this::rules)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void privacyProtectionPrecedesRequiredProtectedInspectionForCallAndStream() {
        for (boolean streaming : List.of(false, true)) {
            AtomicInteger inspections = new AtomicInteger();
            ContentInspector protectedInspector = new ContentInspector() {
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
                    assertThat(request.segments()).allSatisfy(segment -> {
                        assertThat(segment.privacyProcessingStatus())
                                .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED);
                        assertThat(segment.text()).doesNotContain("Alice");
                    });
                    inspections.incrementAndGet();
                    return InspectionResult.completed(
                            request.segments().stream()
                                    .map(ContentSegment::id)
                                    .collect(Collectors.toSet()),
                            List.of());
                }
            };
            runner.withConfiguration(
                            AutoConfigurations.of(PrivacyGuardrailsAutoConfiguration.class))
                    .withPropertyValues("spring.ai.inspection.enabled=true",
                            "spring.ai.inspection.output.enabled=true", "spring.ai.privacy.output.enabled=true")
                    .withBean(ContentInspector.class, () -> protectedInspector)
                    .withBean(PiiAnalyzer.class, this::aliceAnalyzer)
                    .run(
                            context -> {
                                assertThat(context).hasNotFailed();
                                ChatClient client = ModelRequestBoundaryConfigurer.compose(
                                                context.getBean(PrivacyChatClientConfigurer.class)
                                                        .forToolAdvisorOrder(ToolCallingAdvisor.DEFAULT_ORDER),
                                                context.getBean(InspectionChatClientConfigurer.class))
                                        .configure(ChatClient.builder(privacyCheckingModel()))
                                        .build();
                                String result =
                                        streaming
                                                ? client.prompt().user("Hello Alice").stream()
                                                        .content()
                                                        .collectList()
                                                        .block(Duration.ofSeconds(5))
                                                        .get(0)
                                                : client.prompt()
                                                        .user("Hello Alice")
                                                        .call()
                                                        .content();
                                assertThat(result).isEqualTo("done");
                                assertThat(inspections).hasValue(2);
                            });
        }
    }

    private PiiAnalyzer aliceAnalyzer() {
        return (text, options, limits) -> {
            int start = text.indexOf("Alice");
            return start < 0 ? List.of() : List.of(new PiiSpan("PERSON", start, start + 5, 1.0));
        };
    }

    @ParameterizedTest(name = "streaming={0}, failure={1}, finding={2}")
    @CsvSource({
            "false, TIMEOUT, false", "false, TIMEOUT, true",
            "true, TIMEOUT, false", "true, TIMEOUT, true",
            "false, LIMIT_EXCEEDED, false", "false, LIMIT_EXCEEDED, true",
            "true, LIMIT_EXCEEDED, false", "true, LIMIT_EXCEEDED, true"
    })
    void bootObserverSeesFailOpenBlockAndHardFailureWithoutChangingEnforcement(
            boolean streaming, InspectionFailureCode code, boolean findingPresent) {
        AtomicReference<InspectionReport> observedReport = new AtomicReference<>();
        AtomicReference<InspectionException> observedFailure = new AtomicReference<>();
        AtomicInteger modelCalls = new AtomicInteger();
        ContentInspector inspector = new ContentInspector() {
            @Override
            public String inspectorId() {
                return "failing-inspector";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                List<InspectionFinding> findings = findingPresent ? List.of(new InspectionFinding(
                        request.segments().get(0).id(), InspectionFinding.Category.PROMPT_ATTACK, "attack", null))
                        : List.of();
                return InspectionResult.failed(code, Set.of(), findings);
            }
        };
        InspectionObserver observer = new InspectionObserver() {
            public void onInspection(InspectionReport report) {
                observedReport.set(report);
                throw new IllegalStateException("observer unavailable");
            }

            public void onFailure(InspectionException failure) {
                observedFailure.set(failure);
                throw new IllegalStateException("observer unavailable");
            }
        };
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                modelCalls.incrementAndGet();
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> Flux.just(call(prompt)));
            }
        };
        runner.withPropertyValues("spring.ai.inspection.enabled=true", "spring.ai.inspection.failure-policy=FAIL_OPEN")
                .withBean(ContentInspector.class, () -> inspector)
                .withBean(InspectionObserver.class, () -> observer)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ChatClient client = context.getBean(InspectionChatClientConfigurer.class)
                            .configure(ChatClient.builder(model)).build();
                    Runnable invoke = () -> {
                        if (streaming) {
                            client.prompt().user("synthetic").stream().content().collectList().block(Duration.ofSeconds(5));
                        } else {
                            client.prompt().user("synthetic").call().content();
                        }
                    };
                    if (code == InspectionFailureCode.LIMIT_EXCEEDED) {
                        assertThatThrownBy(invoke::run).isInstanceOf(InspectionException.class);
                        assertThat(observedFailure.get().failureCode()).isEqualTo(code);
                        assertThat(observedFailure.get().report().orElseThrow().outcomes().get(0).result().findings())
                                .hasSize(findingPresent ? 1 : 0);
                        assertThat(observedReport.get()).isNull();
                        assertThat(modelCalls).hasValue(0);
                    } else if (findingPresent) {
                        assertThatThrownBy(invoke::run).isInstanceOf(InspectionBlockedException.class);
                        assertThat(observedReport.get().decision()).isEqualTo(InspectionDecision.BLOCK);
                        assertThat(observedFailure.get()).isNull();
                        assertThat(modelCalls).hasValue(0);
                    } else {
                        invoke.run();
                        assertThat(observedReport.get().allowedAfterFailure()).isTrue();
                        assertThat(observedFailure.get()).isNull();
                        assertThat(modelCalls).hasValue(1);
                    }
                });
    }

    private ChatModel privacyCheckingModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                assertThat(prompt.getContents()).doesNotContain("Alice");
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> Flux.just(call(prompt)));
            }
        };
    }
}
