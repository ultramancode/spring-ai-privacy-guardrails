package io.github.ultramancode.springai.privacy.inspection.springai;

import com.sun.net.httpserver.HttpServer;
import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.boundary.PiiTokenSimplifierContext;
import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.OpenAiCompatibleContentInspector;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.OpenAiCompatibleInspectionConfig;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.TextWindowConfig;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol.KananaSafeguardPromptProtocol;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PiiTokenSimplificationIntegrationTest {
    private HttpServer httpServer;
    private final List<String> httpInspectionTexts = new CopyOnWriteArrayList<>();
    private final List<String> unchangedInspectionTexts = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> modelInputText = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1/chat/completions", exchange -> {
            try {
                String inspectionText = jsonMapper.readTree(exchange.getRequestBody().readAllBytes())
                        .path("messages").get(0).path("content").asString();
                httpInspectionTexts.add(inspectionText);
                String verdict = inspectionText.contains("attack") ? "<UNSAFE-A1>" : "<SAFE>";
                byte[] httpResponseBody = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{"
                        + "\"role\":\"assistant\",\"content\":\"" + verdict + "\"}}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, httpResponseBody.length);
                exchange.getResponseBody().write(httpResponseBody);
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
    }

    @AfterEach
    void stopServer() {
        httpServer.stop(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void simplifiesInputAndOutputBeforeHttpWindowingWithoutChangingModelOrApplicationText(boolean streaming) {
        PrivacyService privacyService = privacyService();
        InspectionService inspectionService = inspectionService();
        ChatClient chatClient = chatClient(privacyService, inspectionService);

        List<ChatClientResponse> chatClientResponses;
        if (streaming) {
            chatClientResponses = chatClient.prompt().user("Hello Alice and Bob, Alice").stream().chatClientResponse()
                    .collectList().block(Duration.ofSeconds(30));
        } else {
            chatClientResponses = List.of(chatClient.prompt().user("Hello Alice and Bob, Alice").call().chatClientResponse());
        }
        String outputText = chatClientResponses.stream().map(chatClientResponse -> chatClientResponse.chatResponse().getResult().getOutput().getText())
                .filter(Objects::nonNull).collect(Collectors.joining());

        assertThat(httpInspectionTexts).containsExactly(
                "Hello [PII_PERSON_1] and [PII_PERSON_2], [PII_PERSON_1]", "[PII_PERSON_2] [PII_PERSON_1]");
        assertThat(modelInputText.get()).startsWith("Hello ").containsPattern(OpaquePiiTokenFormat.patternForEntityType("PERSON"));
        String personTokenPattern = OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern();
        assertThat(outputText).matches(personTokenPattern + " " + personTokenPattern);
        assertThat(unchangedInspectionTexts).containsExactly(modelInputText.get(), outputText);
        assertThat(chatClientResponses).allSatisfy(chatClientResponse -> assertThat(PiiTokenSimplifierContext.hasInternalEntries(chatClientResponse.context())).isFalse());
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void preservesAnAttackBesideATokenAndBlocksBeforeTheModelCall() {
        PrivacyService privacyService = privacyService();
        ChatClient chatClient = chatClient(privacyService, inspectionService());

        assertThatThrownBy(() -> chatClient.prompt().user("Alice attack").call().content())
                .isInstanceOf(InspectionBlockedException.class);
        assertThat(httpInspectionTexts).containsExactly("[PII_PERSON_1] attack");
        assertThat(modelInputText.get()).isNull();
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void selectingSimplificationWithoutAPrivacySessionFailsBeforeHttp() {
        ChatClient chatClient = new InspectionChatClientConfigurer(inspectionService())
                .configure(ChatClient.builder(chatModel())).build();

        assertThatThrownBy(() -> chatClient.prompt().user("hello").call().content())
                .isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CONFIGURATION));
        assertThat(httpInspectionTexts).isEmpty();
        assertThat(modelInputText.get()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputSimplificationWithoutPrivacyOutputProtectionFailsBeforeSendingOutputToHttp(boolean streaming) {
        PrivacyService privacyService = privacyService();
        InspectionChatClientConfigurer inspectionChatClientConfigurer = inspectionChatClientConfigurer(inspectionService());
        ChatClient chatClient = ModelRequestBoundaryConfigurer.compose(
                        PrivacyChatClientConfigurer.builder(privacyService).build(), inspectionChatClientConfigurer)
                .configure(ChatClient.builder(chatModel())).build();

        assertThatThrownBy(() -> {
            if (streaming) {
                chatClient.prompt().user("Hello Alice").stream().content().collectList().block(Duration.ofSeconds(30));
            } else {
                chatClient.prompt().user("Hello Alice").call().content();
            }
        }).isInstanceOfSatisfying(InspectionException.class,
                failure -> assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CONFIGURATION));
        assertThat(httpInspectionTexts).containsExactly("Hello [PII_PERSON_1]");
        assertThat(modelInputText.get()).isNotNull();
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    private PrivacyService privacyService() {
        return new PrivacyService(List.of((text, options, limits) -> Pattern.compile("Alice|Bob")
                .matcher(text).results().map(match -> new PiiSpan("PERSON", match.start(), match.end(), 1.0))
                .toList()), PiiAnalysisOptions.defaults());
    }

    private InspectionService inspectionService() {
        ContentInspector unchangedContentInspector = new ContentInspector() {
            public String inspectorId() {
                return "unchanged";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest inspectionRequest) {
                inspectionRequest.segments().forEach(segment -> unchangedInspectionTexts.add(segment.text()));
                return InspectionResult.completed(inspectionRequest.segments().stream().map(ContentSegment::id)
                        .collect(Collectors.toSet()), List.of());
            }
        };
        URI baseUrl = URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort() + "/v1");
        ContentInspector httpContentInspector = new OpenAiCompatibleContentInspector("guard",
                new OpenAiCompatibleInspectionConfig(baseUrl, "fixture", null, Duration.ofSeconds(10), 4096, true),
                new KananaSafeguardPromptProtocol(), new TextWindowConfig(64, 0, 10));
        return new InspectionService(List.of(unchangedContentInspector, httpContentInspector)).withPiiTokenSimplification(Set.of("guard"));
    }

    private InspectionChatClientConfigurer inspectionChatClientConfigurer(InspectionService inspectionService) {
        PrivacyProcessingStatusResolver privacyProcessingStatusResolver = chatClientRequest -> {
            if (PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(chatClientRequest)) {
                return ContentSegment.PrivacyProcessingStatus.PROCESSED;
            }
            return ContentSegment.PrivacyProcessingStatus.UNKNOWN;
        };
        return new InspectionChatClientConfigurer(inspectionService,
                InspectionLimits.defaults(), privacyProcessingStatusResolver, InspectionObserver.noop())
                .withOutputInspection(new InspectionOutputAdvisor(inspectionService));
    }

    private ChatClient chatClient(PrivacyService privacyService, InspectionService inspectionService) {
        PrivacyChatClientConfigurer privacyChatClientConfigurer = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(PrivacyOutputAction.TOKENIZE, "blocked").build();
        return ModelRequestBoundaryConfigurer.compose(privacyChatClientConfigurer, inspectionChatClientConfigurer(inspectionService))
                .configure(ChatClient.builder(chatModel())).build();
    }

    private ChatModel chatModel() {
        return new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                modelInputText.set(prompt.getUserMessage().getText());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("Bob Alice"))));
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    modelInputText.set(prompt.getUserMessage().getText());
                    ChatResponse firstChatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Bob Al"))));
                    ChatResponse secondChatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("ice"))));
                    return Flux.just(firstChatResponse, secondChatResponse);
                });
            }
        };
    }
}
