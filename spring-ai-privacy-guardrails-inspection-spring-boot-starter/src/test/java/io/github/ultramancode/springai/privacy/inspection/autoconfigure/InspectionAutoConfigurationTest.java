package io.github.ultramancode.springai.privacy.inspection.autoconfigure;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionChatClientConfigurer;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(InspectionAutoConfiguration.class));

    private ContentInspector rules() {
        return new RuleBasedContentInspector(List.of(InspectionRule.literal("attack", "attack")));
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
                            "spring.ai.inspection.max-chunks",
                            "spring.ai.inspection.timeout",
                            "spring.ai.inspection.failure-policy");
            assertThat(properties).allSatisfy(property ->
                    assertThat(((Map<?, ?>) property).get("description"))
                            .isInstanceOfSatisfying(String.class, description -> assertThat(description).isNotBlank()));
        }
    }

    @Test
    void disabledByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(InspectionService.class));
    }

    @Test
    void enablingWithoutProvidersFailsStartup() {
        runner.withPropertyValues("spring.ai.inspection.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void runsWithoutPrivacyOrModelBackendDependencies() {
        runner.withClassLoader(
                        new FilteredClassLoader(
                                "io.github.ultramancode.springai.privacy.springai",
                                "ai.onnxruntime",
                                "ai.djl",
                                "io.github.ultramancode.springai.privacy.inspection.openaicompatible"))
                .withPropertyValues("spring.ai.inspection.enabled=true")
                .withBean(ContentInspector.class, this::rules)
                .run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .hasSingleBean(InspectionChatClientConfigurer.class));
    }

    @Test
    void invalidLimitsFailStartup() {
        runner.withPropertyValues(
                        "spring.ai.inspection.enabled=true", "spring.ai.inspection.max-chunks=0")
                .withBean(ContentInspector.class, this::rules)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void privacyProtectionPrecedesRequiredProtectedInspectionForCallAndStream() {
        for (boolean streaming : List.of(false, true)) {
            AtomicInteger inspections = new AtomicInteger();
            ContentInspector protectedInspector =
                    request -> {
                        request.requireProtected();
                        assertThat(request.segments())
                                .allSatisfy(
                                        segment ->
                                                assertThat(segment.text()).doesNotContain("Alice"));
                        inspections.incrementAndGet();
                        return InspectionResult.completed(
                                request.segments().stream()
                                        .map(ContentSegment::id)
                                        .collect(Collectors.toSet()),
                                List.of());
                    };
            runner.withConfiguration(
                            AutoConfigurations.of(PrivacyGuardrailsAutoConfiguration.class))
                    .withPropertyValues("spring.ai.inspection.enabled=true")
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
                                assertThat(inspections).hasValue(1);
                            });
        }
    }

    private PiiAnalyzer aliceAnalyzer() {
        return (text, options) -> {
            int start = text.indexOf("Alice");
            return start < 0 ? List.of() : List.of(new PiiSpan("PERSON", start, start + 5, 1.0));
        };
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
