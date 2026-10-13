package io.github.ultramancode.springai.privacy.inspection.springai;

import com.sun.net.httpserver.HttpServer;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol.KananaSafeguardPromptProtocol;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.OpenAiCompatibleContentInspector;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.OpenAiCompatibleInspectionConfig;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAction;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.core.PriorityOrdered;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionOutputPrivacyIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private final List<String> httpInspectionTexts = new CopyOnWriteArrayList<>();
    private final List<ContentSegment> inspectedOutputSegments = new CopyOnWriteArrayList<>();
    private final AtomicInteger inspectionCount = new AtomicInteger();
    private HttpServer server;
    private InspectionOutputAdvisor inspectionOutputAdvisor;

    @BeforeEach
    void startHttpInspector() throws Exception {
        JsonMapper jsonMapper = JsonMapper.builder().build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                httpInspectionTexts.add(jsonMapper.readTree(exchange.getRequestBody().readAllBytes())
                        .path("messages").get(0).path("content").asString());
                byte[] inspectionResponseBytes = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"<SAFE>\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, inspectionResponseBytes.length);
                exchange.getResponseBody().write(inspectionResponseBytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        ContentInspector httpInspector = new OpenAiCompatibleContentInspector("http",
                new OpenAiCompatibleInspectionConfig(baseUrl, "fixture", null, WAIT, 4096, true),
                new KananaSafeguardPromptProtocol());
        ContentInspector recordingInspector = new ContentInspector() {
            public String inspectorId() {
                return "record-output";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                inspectionCount.incrementAndGet();
                inspectedOutputSegments.addAll(request.segments());
                return InspectionResult.completed(request.segments().stream().map(ContentSegment::id)
                        .collect(Collectors.toSet()), List.of());
            }
        };
        inspectionOutputAdvisor = new InspectionOutputAdvisor(new InspectionService(List.of(recordingInspector, httpInspector)));
    }

    @AfterEach
    void stopHttpInspector() {
        server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({"false,TOKENIZE", "true,TOKENIZE", "false,REDACT", "true,REDACT", "false,BLOCK", "true,BLOCK"})
    void httpInspectionReceivesPrivacyProcessedOutput(boolean streaming, PrivacyOutputAction action) {
        for (boolean jsonOutput : List.of(false, true)) {
            for (String value : List.of("clear", "Alice")) {
                if (action == PrivacyOutputAction.BLOCK && value.equals("Alice")) {
                    continue;
                }
                inspectedOutputSegments.clear();
                httpInspectionTexts.clear();
                PrivacyService privacyService = privacyService(new CopyOnWriteArrayList<>());
                String rawOutputText = jsonOutput ? "{\"attack\":\"" + value + "\",\"n\":123,\"tail\":\"done\"}" : value;
                ChatClient client = PrivacyChatClientConfigurer.builder(privacyService)
                        .outputProtection(action, "blocked").build()
                        .configure(ChatClient.builder(model(rawOutputText))).defaultAdvisors(inspectionOutputAdvisor).build();
                String deliveredOutputText = invoke(client, streaming);
                assertThat(deliveredOutputText).doesNotContain("Alice");
                if (value.equals("clear")) {
                    assertThat(deliveredOutputText).isEqualTo(rawOutputText);
                }
                assertThat(httpInspectionTexts).containsExactly(deliveredOutputText);
                assertThat(inspectedOutputSegments).extracting(ContentSegment::text).containsExactlyElementsOf(httpInspectionTexts);
                assertThat(inspectedOutputSegments).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                        .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
                assertThat(privacyService.activeSessionCount()).isZero();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void httpInspectionRejectsOutputWithoutPrivacyProcessing(boolean streaming) {
        for (boolean inputPrivacyEnabled : List.of(false, true)) {
            PrivacyService privacyService = privacyService(new CopyOnWriteArrayList<>());
            ChatClient.Builder builder = ChatClient.builder(model("Alice"));
            if (inputPrivacyEnabled) {
                new PrivacyChatClientConfigurer(privacyService).configure(builder);
            }
            ChatClient client = builder.defaultAdvisors(inspectionOutputAdvisor).build();
            assertPrivacyProcessingRequired(client, streaming);
            assertThat(privacyService.activeSessionCount()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"isThought", "thinking"})
    void thoughtAndAnswerAreSeparateCompletedTextUnits(String thoughtMarker) {
        List<String> privacyAnalyzedTexts = new CopyOnWriteArrayList<>();
        PrivacyService privacyService = privacyService(privacyAnalyzedTexts);
        ChatResponse thoughtResponse = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("Ali").properties(Map.of(thoughtMarker, true)).build())));
        ChatResponse answerResponse = response("ce");
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                return answerResponse;
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(thoughtResponse, answerResponse);
            }
        };
        ChatClient client = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(PrivacyOutputAction.TOKENIZE, "blocked").build()
                .configure(ChatClient.builder(model)).defaultAdvisors(inspectionOutputAdvisor).build();
        List<ChatClientResponse> responseFrames = client.prompt().user("hello").stream()
                .chatClientResponse().collectList().block(WAIT);
        assertThat(responseFrames).hasSize(2).allSatisfy(frame -> assertThat(frame.context()).isEmpty());
        assertThat(responseFrames.get(0).chatResponse().getResult().getOutput().getMetadata())
                .containsEntry(thoughtMarker, true);
        assertThat(httpInspectionTexts).containsExactly("Ali", "ce");
        assertThat(inspectedOutputSegments).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
        assertThat(privacyAnalyzedTexts).contains("Ali", "ce").doesNotContain("Alice");
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void reorderedChoicesRetainTheirOwnCompletedText() {
        PrivacyService privacyService = privacyService(new CopyOnWriteArrayList<>());
        ChatResponse firstFrame = new ChatResponse(List.of(indexedGeneration(0, "Ali"), indexedGeneration(1, "cl")));
        ChatResponse lastFrame = new ChatResponse(List.of(indexedGeneration(1, "ear"), indexedGeneration(0, "ce")));
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                return firstFrame;
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(firstFrame, lastFrame);
            }
        };
        ChatClient client = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                .configure(ChatClient.builder(model)).defaultAdvisors(inspectionOutputAdvisor).build();
        client.prompt().user("hello").stream().chatResponse().collectList().block(WAIT);
        assertThat(httpInspectionTexts).hasSize(2).contains("clear").doesNotContain("Alice");
        assertThat(inspectedOutputSegments).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"false,1", "true,1", "false,2", "true,2"})
    void returnDirectProtectionCompletesBeforeHttpInspection(boolean streaming, int toolResultCount) {
        PrivacyService privacyService = privacyService(new CopyOnWriteArrayList<>());
        ToolCallback lookupToolCallback = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("fixture").inputSchema("{}").build();
            }

            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(true).build();
            }

            public String call(String arguments) {
                return "Hello Alice";
            }
        };
        ToolCallback privacyProtectedToolCallback =
                new PrivacyToolCallbackFactory(privacyService, ToolDisclosurePolicy.denyAll()).wrap(lookupToolCallback);
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (int index = 0; index < toolResultCount; index++) {
            toolCalls.add(new AssistantMessage.ToolCall("id-" + index, "function", "lookup", "{}"));
        }
        ChatModel model = new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(toolCalls).build())));
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(new ChatResponse(List.of(indexedGeneration(7, "Checking Alice. "))), call(prompt));
            }
        };
        ChatClient client = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                .configure(ChatClient.builder(model)).defaultAdvisors(inspectionOutputAdvisor).defaultTools(privacyProtectedToolCallback).build();
        String deliveredOutputText = invoke(client, streaming);
        assertThat(deliveredOutputText).contains("Hello ").doesNotContain("Alice");
        if (streaming) {
            assertThat(httpInspectionTexts).hasSize(toolResultCount + 1);
            assertThat(httpInspectionTexts.get(0)).startsWith("Checking ").endsWith(". ");
            assertThat(deliveredOutputText).isEqualTo(httpInspectionTexts.get(0) + httpInspectionTexts.get(1));
        } else {
            assertThat(httpInspectionTexts).hasSize(toolResultCount);
            assertThat(deliveredOutputText).isEqualTo(httpInspectionTexts.get(0));
        }
        assertThat(httpInspectionTexts).allSatisfy(text -> assertThat(text).doesNotContain("Alice", "[[PII_"));
        assertThat(inspectedOutputSegments).extracting(ContentSegment::text).containsExactlyElementsOf(httpInspectionTexts);
        assertThat(inspectedOutputSegments).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
        assertThat(inspectionCount).hasValue(1);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void toolLoopTextIsProtectedTogetherBeforeHttpInspection() {
        PrivacyService privacyService = privacyService(new CopyOnWriteArrayList<>());
        ToolCallback lookupToolCallback = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("fixture").inputSchema("{}").build();
            }

            public String call(String arguments) {
                return "found";
            }
        };
        ToolCallback privacyProtectedToolCallback =
                new PrivacyToolCallbackFactory(privacyService, ToolDisclosurePolicy.denyAll()).wrap(lookupToolCallback);
        ChatModel model = new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                throw new AssertionError("stream only");
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                boolean hasToolResponse = prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
                if (hasToolResponse) {
                    return Flux.just(new ChatResponse(List.of(indexedGeneration(7, "Found the result."))));
                }
                ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "lookup", "{}"))).build())));
                return Flux.just(new ChatResponse(List.of(indexedGeneration(7, "Checking Ali"))),
                        new ChatResponse(List.of(indexedGeneration(7, "ce. "))), toolCallResponse);
            }
        };
        ChatClient client = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                .configure(ChatClient.builder(model)).defaultAdvisors(inspectionOutputAdvisor).defaultTools(privacyProtectedToolCallback).build();
        String deliveredOutputText = invoke(client, true);
        assertThat(deliveredOutputText).startsWith("Checking ").endsWith(". Found the result.").doesNotContain("Alice");
        assertThat(httpInspectionTexts).containsExactly(deliveredOutputText);
        assertThat(inspectedOutputSegments).singleElement().satisfies(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
        assertThat(inspectionCount).hasValue(1);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void changedOutputCannotReusePrivacyProcessingStatus(boolean streaming, boolean duplicateFrames) {
        PrivacyService privacyService = privacyService(new CopyOnWriteArrayList<>());
        ChatClient client = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(PrivacyOutputAction.TOKENIZE, "blocked").build()
                .configure(ChatClient.builder(model("clear")))
                .defaultAdvisors(inspectionOutputAdvisor, new OutputMutationAdvisor(duplicateFrames)).build();
        assertPrivacyProcessingRequired(client, streaming);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    private void assertPrivacyProcessingRequired(ChatClient client, boolean streaming) {
        assertThatThrownBy(() -> invoke(client, streaming)).isInstanceOf(InspectionException.class)
                .satisfies(error -> assertThat(((InspectionException) error).failureCode())
                        .isEqualTo(InspectionFailureCode.PRIVACY_PROCESSING_REQUIRED));
        assertThat(httpInspectionTexts).isEmpty();
        assertThat(inspectedOutputSegments).isEmpty();
    }

    private PrivacyService privacyService(List<String> privacyAnalyzedTexts) {
        PiiAnalyzer analyzer = (text, options, limits) -> {
            privacyAnalyzedTexts.add(text);
            int at = text.indexOf("Alice");
            return at < 0 ? List.of() : List.of(new PiiSpan("PERSON", at, at + 5, 1.0));
        };

        return new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
    }

    private ChatModel model(String rawOutputText) {
        return new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                return response(rawOutputText);
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                int middle = rawOutputText.length() / 2;
                return Flux.just(response(rawOutputText.substring(0, middle)), response(rawOutputText.substring(middle)));
            }
        };
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private Generation indexedGeneration(int choiceIndex, String text) {
        return new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder().metadata("index", choiceIndex).build());
    }

    private String invoke(ChatClient client, boolean streaming) {
        if (streaming) {
            List<ChatClientResponse> responseFrames = client.prompt().user("hello").stream()
                    .chatClientResponse().collectList().block(WAIT);
            assertThat(responseFrames).allSatisfy(frame -> assertThat(frame.context()).isEmpty());
            return responseFrames.stream().map(frame -> frame.chatResponse().getResult().getOutput().getText())
                    .filter(text -> text != null).collect(Collectors.joining());
        }
        ChatClientResponse response = client.prompt().user("hello").call().chatClientResponse();
        assertThat(response.context()).isEmpty();
        return response.chatResponse().getResult().getOutput().getText();
    }

    /** Runs between the Privacy lifecycle and output inspection. */
    private final class OutputMutationAdvisor implements CallAdvisor, StreamAdvisor, PriorityOrdered {
        private final boolean duplicateFrames;

        private OutputMutationAdvisor(boolean duplicateFrames) {
            this.duplicateFrames = duplicateFrames;
        }

        public String getName() {
            return "OutputMutationAdvisor";
        }

        public int getOrder() {
            return InspectionOutputAdvisor.DEFAULT_ORDER + 1;
        }

        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            return replace(chain.nextCall(request));
        }

        public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
            Flux<ChatClientResponse> responseStream = chain.nextStream(request);
            if (duplicateFrames) {
                return responseStream.concatMap(response -> Flux.just(response, response));
            }
            return responseStream.map(this::replace);
        }

        private ChatClientResponse replace(ChatClientResponse originalResponse) {
            return originalResponse.mutate().chatResponse(response("new unprocessed text")).build();
        }
    }
}
