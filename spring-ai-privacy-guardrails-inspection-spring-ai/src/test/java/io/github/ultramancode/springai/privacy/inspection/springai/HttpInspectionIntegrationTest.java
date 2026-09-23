package io.github.ultramancode.springai.privacy.inspection.springai;

import com.sun.net.httpserver.HttpServer;
import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.OpenAiCompatibleContentInspector;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.OpenAiCompatibleInspectionConfig;
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
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpInspectionIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void privacyProcessesContentBeforeHttpAndHttpBlockPreventsBusinessCall(boolean streaming) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> sentBodies = new CopyOnWriteArrayList<>();
        AtomicReference<String> label = new AtomicReference<>("<SAFE>");
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                sentBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String json = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                        + label.get() + "\"}}]}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            PiiAnalyzer analyzer = (text, options) -> {
                int start = text.indexOf("Alice");
                return start < 0 ? List.of() : List.of(new PiiSpan("PERSON", start, start + 5, 1.0));
            };
            PrivacyService privacy = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            var inspector = OpenAiCompatibleContentInspector.kanana("guard", new OpenAiCompatibleInspectionConfig(
                    endpoint, "fixture", null, Duration.ofSeconds(5), 4096, false));
            var inspection = new InspectionChatClientConfigurer(new InspectionService(List.of(inspector)),
                    InspectionLimits.defaults(),
                    request -> PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(request)
                            ? ContentSegment.PrivacyProcessingStatus.PROCESSED : ContentSegment.PrivacyProcessingStatus.UNKNOWN,
                    InspectionObserver.noop());
            AtomicInteger businessCalls = new AtomicInteger();
            ChatModel model = new ChatModel() {
                public ChatResponse call(Prompt prompt) {
                    businessCalls.incrementAndGet();
                    assertThat(prompt.getContents()).doesNotContain("Alice");
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                }
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.defer(() -> Flux.just(call(prompt)));
                }
            };
            ChatClient client = ModelRequestBoundaryConfigurer.compose(inspection, new PrivacyChatClientConfigurer(privacy))
                    .configure(ChatClient.builder(model)).build();
            Runnable invoke = () -> {
                if (streaming) {
                    client.prompt().user("Hello Alice").stream().content().collectList().block(Duration.ofSeconds(10));
                } else {
                    client.prompt().user("Hello Alice").call().content();
                }
            };
            invoke.run();
            assertThat(businessCalls).hasValue(1);
            label.set("<UNSAFE-A1>");
            assertThatThrownBy(invoke::run).isInstanceOf(InspectionBlockedException.class);
            assertThat(businessCalls).hasValue(1);
            assertThat(sentBodies).hasSize(2).allSatisfy(body -> assertThat(body).contains("Hello").doesNotContain("Alice"));
            assertThat(privacy.activeSessionCount()).isZero();
        } finally {
            server.stop(0);
        }
    }
}
