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
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.KananaPromptProtocol;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.OpenAiCompatibleContentInspector;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.OpenAiCompatibleInspectionConfig;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding.Category.PROMPT_ATTACK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxInspectionIntegrationTest {

    @TempDir Path temp;

    private Path copy(String name, boolean base64) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/" + name)) {
            byte[] bytes = input.readAllBytes();
            return Files.write(temp.resolve(name), base64 ? Base64.getMimeDecoder().decode(bytes) : bytes);
        }
    }

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
        try (OnnxContentInspector onnx = new OnnxContentInspector("local",
                OnnxInspectionConfig.defaults(copy("binary.onnx.base64", true)),
                OnnxClassificationConfig.promptGuard2(copy("tokenizer.json", false),
                        copy("tokenizer_config.json", false), 0.5))) {
            PiiAnalyzer analyzer = (text, options, limits) -> {
                int start = text.indexOf("Alice");
                return start < 0 ? List.of() : List.of(new PiiSpan("PERSON", start, start + 5, 1.0));
            };
            PrivacyService privacy = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
            RuleBasedContentInspector rules = new RuleBasedContentInspector("rules", List.of(
                    InspectionRule.literal("literal", PROMPT_ATTACK, "forbidden")));
            OpenAiCompatibleContentInspector http = new OpenAiCompatibleContentInspector("remote",
                    new OpenAiCompatibleInspectionConfig(URI.create("http://127.0.0.1:"
                            + server.getAddress().getPort() + "/v1/chat/completions"),
                            "fixture", null, Duration.ofSeconds(3), 4096, true), new KananaPromptProtocol());
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
            invoke(client, "hello Alice", streaming);
            assertThat(calls).hasValue(1);
            assertThat(httpCalls).hasValue(1);
            assertThat(observed.get().outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                    .containsExactly("rules", "local", "remote");

            assertThatThrownBy(() -> invoke(client, "forbidden Alice", streaming))
                    .isInstanceOf(InspectionBlockedException.class);
            assertThat(observed.get().outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                    .containsExactly("rules");

            assertThatThrownBy(() -> invoke(client, "hello ".repeat(1100) + "attack Alice", streaming))
                    .isInstanceOf(InspectionBlockedException.class);
            assertThat(observed.get().outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                    .containsExactly("rules", "local");
            assertThat(httpCalls).hasValue(1);

            label.set("<UNSAFE-A1>");
            assertThatThrownBy(() -> invoke(client, "hello Alice", streaming))
                    .isInstanceOf(InspectionBlockedException.class);
            assertThat(httpCalls).hasValue(2);
            assertThat(calls).hasValue(1);
            assertThat(privacy.activeSessionCount()).isZero();
        } finally {
            server.stop(0);
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
