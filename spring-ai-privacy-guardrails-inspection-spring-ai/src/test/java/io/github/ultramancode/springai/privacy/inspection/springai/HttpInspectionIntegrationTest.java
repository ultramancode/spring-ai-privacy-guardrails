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
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol.KananaSafeguardPromptProtocol;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.OpenAiCompatibleContentInspector;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.OpenAiCompatibleInspectionConfig;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

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
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void privacyProcessesInputBeforeHttpInspectionAndBlocksModelCall(boolean streaming, boolean jsonInput) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> inspectionRequestBodies = new CopyOnWriteArrayList<>();
        JsonMapper jsonMapper = JsonMapper.builder().build();
        AtomicReference<String> inspectionVerdict = new AtomicReference<>("<SAFE>");
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                inspectionRequestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String json = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""
                        + inspectionVerdict.get() + "\"}}]}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            PiiAnalyzer analyzer = (text, options, limits) -> {
                int start = text.indexOf("Alice");
                return start < 0 ? List.of() : List.of(new PiiSpan("PERSON", start, start + 5, 1.0));
            };
            PrivacyService privacyService = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
            URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector("guard", new OpenAiCompatibleInspectionConfig(
                    baseUrl, "fixture", null, Duration.ofSeconds(5), 4096, true), new KananaSafeguardPromptProtocol());
            InspectionChatClientConfigurer inspectionConfigurer = new InspectionChatClientConfigurer(new InspectionService(List.of(inspector)),
                    InspectionLimits.defaults(),
                    request -> PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(request)
                            ? ContentSegment.PrivacyProcessingStatus.PROCESSED : ContentSegment.PrivacyProcessingStatus.UNKNOWN,
                    InspectionObserver.noop());
            AtomicInteger modelCalls = new AtomicInteger();
            ChatModel model = new ChatModel() {
                public ChatResponse call(Prompt prompt) {
                    modelCalls.incrementAndGet();
                    assertThat(prompt.getContents()).doesNotContain("Alice");
                    String lastInspectionRequestBody =
                            inspectionRequestBodies.get(inspectionRequestBodies.size() - 1);
                    String inspectedText = jsonMapper.readTree(lastInspectionRequestBody)
                            .path("messages").get(0).path("content").asString();
                    assertThat(inspectedText).isEqualTo(prompt.getUserMessage().getText());
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
                }
                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.defer(() -> Flux.just(call(prompt)));
                }
            };
            ChatClient client = ModelRequestBoundaryConfigurer.compose(inspectionConfigurer, new PrivacyChatClientConfigurer(privacyService))
                    .configure(ChatClient.builder(model)).build();
            String userInput = jsonInput ? "{\"key@example.com\":\"Alice\",\"repeat\":\"Alice\",\"repeat\":\"Alice\"}" : "Hello Alice";
            Runnable invokeChatClient = () -> {
                if (streaming) {
                    client.prompt().user(userInput).stream().content().collectList().block(Duration.ofSeconds(10));
                } else {
                    client.prompt().user(userInput).call().content();
                }
            };
            invokeChatClient.run();
            assertThat(modelCalls).hasValue(1);
            inspectionVerdict.set("<UNSAFE-A1>");
            assertThatThrownBy(invokeChatClient::run).isInstanceOf(InspectionBlockedException.class);
            assertThat(modelCalls).hasValue(1);
            assertThat(inspectionRequestBodies).hasSize(2).allSatisfy(body -> {
                String inspectedText = jsonMapper.readTree(body).path("messages").get(0).path("content").asString();
                assertThat(inspectedText).doesNotContain("Alice");
                if (jsonInput) {
                    assertThat(inspectedText).startsWith("{\"key@example.com\":\"").endsWith("\"}");
                } else {
                    assertThat(inspectedText).startsWith("Hello ");
                }
            });
            assertThat(privacyService.activeSessionCount()).isZero();
        } finally {
            server.stop(0);
        }
    }
}
