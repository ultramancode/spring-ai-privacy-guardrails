package io.github.ultramancode.springai.privacy.inspection.springai;

import com.sun.net.httpserver.HttpServer;
import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.onnx.OnnxClassificationConfig;
import io.github.ultramancode.springai.privacy.inspection.onnx.OnnxContentInspector;
import io.github.ultramancode.springai.privacy.inspection.onnx.OnnxInspectionConfig;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol.KananaSafeguardPromptProtocol;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.OpenAiCompatibleContentInspector;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.OpenAiCompatibleInspectionConfig;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding.Category.PROMPT_ATTACK;
import static io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding.Category.TOXICITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxInspectionIntegrationTest {

    private static final String SAFE = "Thank you for your help. ";
    private static final String INSULT = "\u0422\u044b \u0438\u0434\u0438\u043e\u0442. ";
    private static final Path MODEL_DIRECTORY = Path.of(System.getProperty("inspection.test.model"));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void privacyRulesOnnxAndHttpEnforceBeforeEveryBusinessCall(boolean streaming) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger httpCalls = new AtomicInteger();
        AtomicReference<String> label = new AtomicReference<>("<SAFE>");
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(body).doesNotContain("Alice");
                httpCalls.incrementAndGet();
                byte[] response = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{"
                        + "\"role\":\"assistant\",\"content\":\"" + label.get() + "\"}}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (OnnxContentInspector onnx = localInspector()) {
            PiiAnalyzer analyzer = (text, options, limits) -> {
                int start = text.indexOf("Alice");
                return start < 0 ? List.of() : List.of(new PiiSpan("PERSON", start, start + 5, 1.0));
            };
            PrivacyService privacy = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
            RuleBasedContentInspector rules = new RuleBasedContentInspector("rules", List.of(
                    InspectionRule.literal("literal", PROMPT_ATTACK, "forbidden")));
            OpenAiCompatibleContentInspector http = new OpenAiCompatibleContentInspector("remote",
                    new OpenAiCompatibleInspectionConfig(URI.create("http://127.0.0.1:"
                            + server.getAddress().getPort() + "/v1"),
                            "fixture", null, Duration.ofSeconds(3), 4096, true), new KananaSafeguardPromptProtocol());
            AtomicReference<InspectionReport> observed = new AtomicReference<>();
            InspectionChatClientConfigurer inspection = new InspectionChatClientConfigurer(
                    new InspectionService(List.of(rules, onnx, http)), InspectionLimits.defaults(),
                    request -> PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(request)
                            ? ContentSegment.PrivacyProcessingStatus.PROCESSED
                            : ContentSegment.PrivacyProcessingStatus.UNKNOWN,
                    observed::set);
            AtomicInteger calls = new AtomicInteger();
            ChatModel model = new ChatModel() {
                public ChatResponse call(Prompt prompt) {
                    calls.incrementAndGet();
                    assertThat(prompt.getContents()).doesNotContain("Alice");
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                }
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.defer(() -> Flux.just(call(prompt)));
                }
            };
            ChatClient client = ModelRequestBoundaryConfigurer.compose(inspection, new PrivacyChatClientConfigurer(privacy))
                    .configure(ChatClient.builder(model)).build();
            invoke(client, SAFE + "Alice", streaming);
            assertThat(calls).hasValue(1);
            assertThat(httpCalls).hasValue(1);
            assertThat(observed.get().outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                    .containsExactly("rules", "local", "remote");

            assertThatThrownBy(() -> invoke(client, "forbidden Alice", streaming))
                    .isInstanceOf(InspectionBlockedException.class);
            assertThat(observed.get().outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                    .containsExactly("rules");

            assertThatThrownBy(() -> invoke(client, SAFE.repeat(40) + INSULT.repeat(20) + "Alice", streaming))
                    .isInstanceOf(InspectionBlockedException.class);
            assertThat(observed.get().outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                    .containsExactly("rules", "local");
            assertThat(observed.get().outcomes().get(1).result().findings())
                    .extracting(finding -> finding.category()).containsExactly(TOXICITY);
            assertThat(httpCalls).hasValue(1);

            label.set("<UNSAFE-A1>");
            assertThatThrownBy(() -> invoke(client, SAFE + "Alice", streaming))
                    .isInstanceOf(InspectionBlockedException.class);
            assertThat(httpCalls).hasValue(2);
            assertThat(calls).hasValue(1);
            assertThat(privacy.activeSessionCount()).isZero();
        } finally {
            server.stop(0);
        }
    }

    private OnnxContentInspector localInspector() {
        return new OnnxContentInspector("local",
                OnnxInspectionConfig.defaults(MODEL_DIRECTORY.resolve("model.onnx")),
                OnnxClassificationConfig.builder()
                        .tokenizer(MODEL_DIRECTORY.resolve("tokenizer.json"))
                        .tokenizerConfig(MODEL_DIRECTORY.resolve("tokenizer_config.json"))
                        .maxTokens(128)
                        .overlapTokens(32)
                        .logitCount(5)
                        .activation(OnnxClassificationConfig.Activation.SIGMOID)
                        .labels(List.of(new OnnxClassificationConfig.Label(1, TOXICITY, "INSULT", 0.8)))
                        .build());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onnxOutputInspectionBlocksTheTailBeforeReleasingAnyResponse(boolean streaming) {
        try (OnnxContentInspector onnx = localInspector()) {
            InspectionService service = new InspectionService(List.of(onnx));
            InspectionChatClientConfigurer inspection = new InspectionChatClientConfigurer(service)
                    .withOutputInspection(new InspectionOutputAdvisor(service));
            String prefix = SAFE.repeat(40);
            AtomicReference<String> tail = new AtomicReference<>("world");
            ChatModel model = new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return new ChatResponse(List.of(new Generation(new AssistantMessage(prefix + tail.get()))));
                }

                @Override
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.defer(() -> Flux.just(
                            new ChatResponse(List.of(new Generation(new AssistantMessage(prefix)))),
                            new ChatResponse(List.of(new Generation(new AssistantMessage(tail.get()))))));
                }
            };
            ChatClient client = inspection.configure(ChatClient.builder(model)).build();
            List<String> delivered = new ArrayList<>();
            Runnable invoke = () -> {
                if (streaming) {
                    client.prompt().user(SAFE).stream().content().doOnNext(delivered::add)
                            .blockLast(Duration.ofSeconds(10));
                } else {
                    delivered.add(client.prompt().user(SAFE).call().content());
                }
            };

            invoke.run();
            assertThat(String.join("", delivered)).isEqualTo(prefix + "world");
            delivered.clear();
            tail.set(INSULT.repeat(20));

            assertThatThrownBy(invoke::run).isInstanceOf(InspectionBlockedException.class);
            assertThat(delivered).isEmpty();
        }
    }

    private void invoke(ChatClient client, String text, boolean streaming) {
        if (streaming) {
            client.prompt().user(text).stream().content().collectList().block(Duration.ofSeconds(10));
        } else {
            client.prompt().user(text).call().content();
        }
    }
}
