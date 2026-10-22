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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies that HTTP output inspection receives the output after Privacy protection completes. */
class InspectionOutputPrivacyIntegrationTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration HTTP_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private final List<String> httpInspectionTexts = new CopyOnWriteArrayList<>();
    private final List<ContentSegment> inspectedOutputSegments = new CopyOnWriteArrayList<>();
    private final AtomicInteger recordingInspectorInvocationCount = new AtomicInteger();
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
                // Always return SAFE so these tests isolate Privacy processing and the HTTP payload.
                byte[] inspectionResponseBytes = """
                        {
                          "choices": [
                            {
                              "finish_reason": "stop",
                              "message": {
                                "role": "assistant",
                                "content": "<SAFE>"
                              }
                            }
                          ]
                        }
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, inspectionResponseBytes.length);
                exchange.getResponseBody().write(inspectionResponseBytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        // The final true requires Privacy-processed content before any HTTP request.
        // It does not enable Privacy output protection on the ChatClient.
        ContentInspector httpInspector = new OpenAiCompatibleContentInspector("http",
                new OpenAiCompatibleInspectionConfig(baseUrl, "fixture", null, HTTP_REQUEST_TIMEOUT, 4096, true),
                new KananaSafeguardPromptProtocol());
        ContentInspector recordingInspector = new ContentInspector() {
            public String inspectorId() {
                return "record-output";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                // One invocation records every segment. The HTTP inspector sends each segment separately.
                recordingInspectorInvocationCount.incrementAndGet();
                inspectedOutputSegments.addAll(request.segments());
                return InspectionResult.completed(request.segments().stream().map(ContentSegment::id)
                        .collect(Collectors.toSet()), List.of());
            }
        };
        // InspectionService checks every inspector's Privacy requirement before running either inspector.
        inspectionOutputAdvisor = new InspectionOutputAdvisor(new InspectionService(List.of(recordingInspector, httpInspector)));
    }

    @AfterEach
    void stopHttpInspector() {
        server.stop(0);
    }

    // BLOCK cases use clear text because detected PII must be blocked before HTTP inspection.
    @ParameterizedTest(name = "streaming={0}, action={1}, jsonOutput={2}, value={3}")
    @CsvSource(textBlock = """
            # streaming, action,   jsonOutput, value
            false, TOKENIZE, false, clear
            false, TOKENIZE, false, Alice
            false, TOKENIZE, true,  clear
            false, TOKENIZE, true,  Alice
            true,  TOKENIZE, false, clear
            true,  TOKENIZE, false, Alice
            true,  TOKENIZE, true,  clear
            true,  TOKENIZE, true,  Alice
            false, REDACT,   false, clear
            false, REDACT,   false, Alice
            false, REDACT,   true,  clear
            false, REDACT,   true,  Alice
            true,  REDACT,   false, clear
            true,  REDACT,   false, Alice
            true,  REDACT,   true,  clear
            true,  REDACT,   true,  Alice
            false, BLOCK,    false, clear
            false, BLOCK,    true,  clear
            true,  BLOCK,    false, clear
            true,  BLOCK,    true,  clear
            """)
    void httpInspectionReceivesPrivacyProcessedOutput(
            boolean streaming, PrivacyOutputAction action, boolean jsonOutput, String value) {
        PrivacyService privacyService = privacyService(new CopyOnWriteArrayList<>());
        String rawOutputText = jsonOutput ? """
                {"attack":"%s","n":123,"tail":"done"}
                """.formatted(value).strip() : value;
        // Enable Privacy output protection separately from output inspection.
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

    @ParameterizedTest(name = "streaming={0}, inputPrivacyEnabled={1}")
    @CsvSource(textBlock = """
            # streaming, inputPrivacyEnabled
            false, false
            false, true
            true,  false
            true,  true
            """)
    void httpInspectionRejectsOutputWithoutPrivacyProcessing(boolean streaming, boolean inputPrivacyEnabled) {
        PrivacyService privacyService = privacyService(new CopyOnWriteArrayList<>());
        ChatClient.Builder builder = ChatClient.builder(model("Alice"));
        if (inputPrivacyEnabled) {
            // The default configurer protects inputs. Output protection remains disabled.
            new PrivacyChatClientConfigurer(privacyService).configure(builder);
        }
        // Output inspection is enabled in every case and requires output Privacy processing.
        ChatClient client = builder.defaultAdvisors(inspectionOutputAdvisor).build();
        assertPrivacyProcessingRequired(client, streaming);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"isThought", "thinking"})
    void thoughtAndAnswerAreSeparateCompletedTextUnits(String thoughtMarker) {
        List<String> privacyAnalyzedTexts = new CopyOnWriteArrayList<>();
        PrivacyService privacyService = privacyService(privacyAnalyzedTexts);
        // Thought and answer are separate text units. They must not be joined into Alice for inspection.
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
                .chatClientResponse().collectList().block(TEST_TIMEOUT);
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
        // Choice 0 forms Alice and choice 1 forms clear even when their positions change.
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
        client.prompt().user("hello").stream().chatResponse().collectList().block(TEST_TIMEOUT);
        assertThat(httpInspectionTexts).containsExactlyInAnyOrder("[REDACTED_PERSON]", "clear");
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
                return ToolDefinition.builder().name("lookup").description("fixture").inputSchema("""
                        {}
                        """.strip()).build();
            }

            public ToolMetadata getToolMetadata() {
                // Tool results become the final output without another model call.
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
            toolCalls.add(new AssistantMessage.ToolCall("id-" + index, "function", "lookup", """
                    {}
                    """.strip()));
        }
        // Use a choice index different from its position in the response list.
        int modelChoiceIndex = 7;
        ChatModel model = new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(toolCalls).build())));
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.just(new ChatResponse(List.of(indexedGeneration(modelChoiceIndex, "Checking Alice. "))), call(prompt));
            }
        };
        ChatClient client = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                .configure(ChatClient.builder(model)).defaultAdvisors(inspectionOutputAdvisor).defaultTools(privacyProtectedToolCallback).build();
        String deliveredOutputText = invoke(client, streaming);
        String expectedProtectedToolResultText = "Hello [REDACTED_PERSON]";
        List<String> expectedHttpInspectionTexts =
                new ArrayList<>(Collections.nCopies(toolResultCount, expectedProtectedToolResultText));
        // invoke() reads the first tool result and includes preceding model text when streaming.
        // HTTP inspection covers that model text and every tool result.
        String expectedDeliveredOutputText = expectedProtectedToolResultText;
        if (streaming) {
            // Streaming adds the model's preceding text as a separate segment before the tool results.
            String expectedProtectedModelText = "Checking [REDACTED_PERSON]. ";
            expectedHttpInspectionTexts.add(0, expectedProtectedModelText);
            expectedDeliveredOutputText = expectedProtectedModelText + expectedProtectedToolResultText;
        }
        assertThat(httpInspectionTexts).containsExactlyElementsOf(expectedHttpInspectionTexts);
        assertThat(deliveredOutputText).isEqualTo(expectedDeliveredOutputText);
        assertThat(inspectedOutputSegments).extracting(ContentSegment::text).containsExactlyElementsOf(httpInspectionTexts);
        assertThat(inspectedOutputSegments).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
        assertThat(recordingInspectorInvocationCount).hasValue(1);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void toolLoopTextIsProtectedTogetherBeforeHttpInspection() {
        PrivacyService privacyService = privacyService(new CopyOnWriteArrayList<>());
        ToolCallback lookupToolCallback = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("fixture").inputSchema("""
                        {}
                        """.strip()).build();
            }

            public String call(String arguments) {
                return "found";
            }
        };
        ToolCallback privacyProtectedToolCallback =
                new PrivacyToolCallbackFactory(privacyService, ToolDisclosurePolicy.denyAll()).wrap(lookupToolCallback);
        // Use the same choice index for model text before and after the tool call.
        int modelChoiceIndex = 7;
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
                    return Flux.just(new ChatResponse(List.of(indexedGeneration(modelChoiceIndex, "Found the result."))));
                }
                ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "lookup", """
                                {}
                                """.strip()))).build())));
                // Split Alice into Ali and ce to verify Privacy detects the name after joining the fragments.
                return Flux.just(new ChatResponse(List.of(indexedGeneration(modelChoiceIndex, "Checking Ali"))),
                        new ChatResponse(List.of(indexedGeneration(modelChoiceIndex, "ce. "))), toolCallResponse);
            }
        };
        ChatClient client = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                .configure(ChatClient.builder(model)).defaultAdvisors(inspectionOutputAdvisor).defaultTools(privacyProtectedToolCallback).build();
        String deliveredOutputText = invoke(client, true);
        assertThat(deliveredOutputText).isEqualTo("Checking [REDACTED_PERSON]. Found the result.");
        assertThat(httpInspectionTexts).containsExactly(deliveredOutputText);
        assertThat(inspectedOutputSegments).singleElement().satisfies(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
        assertThat(recordingInspectorInvocationCount).hasValue(1);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void changedOutputCannotReusePrivacyProcessingStatus(boolean streaming, boolean duplicateFrames) {
        PrivacyService privacyService = privacyService(new CopyOnWriteArrayList<>());
        // Replacing the response or duplicating its text invalidates the completed Privacy processing.
        ChatClient client = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(PrivacyOutputAction.TOKENIZE, "blocked").build()
                .configure(ChatClient.builder(model("clear")))
                .defaultAdvisors(inspectionOutputAdvisor, new OutputMutationAdvisor(duplicateFrames)).build();
        assertPrivacyProcessingRequired(client, streaming);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    private void assertPrivacyProcessingRequired(ChatClient client, boolean streaming) {
        assertThatThrownBy(() -> invoke(client, streaming)).isInstanceOf(InspectionException.class)
                .satisfies(error -> assertThat(((InspectionException) error).code())
                        .isEqualTo(InspectionFailureCode.PRIVACY_PROCESSING_REQUIRED));
        // The shared prerequisite fails before both the HTTP inspector and the recording inspector run.
        assertThat(httpInspectionTexts).isEmpty();
        assertThat(inspectedOutputSegments).isEmpty();
    }

    private PrivacyService privacyService(List<String> privacyAnalyzedTexts) {
        // Treat only Alice as PII to make the expected transformations deterministic.
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
                // Split the raw output so Privacy and inspection must handle text across frames.
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
        // These tests add no custom context entries.
        // An empty response context confirms that internal Privacy tracking was removed.
        if (streaming) {
            List<ChatClientResponse> responseFrames = client.prompt().user("hello").stream()
                    .chatClientResponse().collectList().block(TEST_TIMEOUT);
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
