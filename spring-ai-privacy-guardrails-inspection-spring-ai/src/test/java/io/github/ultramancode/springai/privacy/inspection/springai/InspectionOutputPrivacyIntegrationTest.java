package io.github.ultramancode.springai.privacy.inspection.springai;

import com.sun.net.httpserver.HttpServer;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.KananaPromptProtocol;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionOutputPrivacyIntegrationTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private final List<String> sentTexts = new CopyOnWriteArrayList<>();
    private final List<ContentSegment> inspected = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private InspectionOutputAdvisor output;

    @BeforeEach
    void startHttpInspector() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/guard", exchange -> {
            try {
                sentTexts.add(json.readTree(exchange.getRequestBody().readAllBytes())
                        .path("messages").get(0).path("content").asString());
                byte[] bytes = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"<SAFE>\"}}]}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/guard");
        ContentInspector http = new OpenAiCompatibleContentInspector("http",
                new OpenAiCompatibleInspectionConfig(endpoint, "fixture", null, WAIT, 4096, true),
                new KananaPromptProtocol());
        ContentInspector recorder = new ContentInspector() {
            public String inspectorId() { return "record-output"; }
            public boolean requiresPrivacyProcessedContent() { return false; }
            public InspectionResult inspect(InspectionRequest request) {
                inspected.addAll(request.segments());
                return InspectionResult.completed(request.segments().stream().map(ContentSegment::id)
                        .collect(Collectors.toSet()), List.of());
            }
        };
        output = new InspectionOutputAdvisor(new InspectionService(List.of(recorder, http)));
    }

    @AfterEach
    void stopHttpInspector() {
        server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({"false,TOKENIZE", "true,TOKENIZE", "false,REDACT", "true,REDACT", "false,BLOCK", "true,BLOCK"})
    void completedOutputReachesHttpWhetherOrNotItsTextChanges(boolean streaming, PrivacyOutputAction action) {
        for (boolean structured : List.of(false, true)) {
            for (String value : List.of("clear", "Alice")) {
                if (action == PrivacyOutputAction.BLOCK && value.equals("Alice")) {
                    continue;
                }
                inspected.clear();
                sentTexts.clear();
                PrivacyService privacy = privacyService(new CopyOnWriteArrayList<>());
                String raw = structured ? "{\"attack\":\"" + value + "\",\"n\":123,\"tail\":\"done\"}" : value;
                ChatClient client = PrivacyChatClientConfigurer.builder(privacy)
                        .outputProtection(action, "blocked").build()
                        .configure(ChatClient.builder(model(raw))).defaultAdvisors(output).build();
                String delivered = invoke(client, streaming);
                assertThat(delivered).doesNotContain("Alice");
                if (value.equals("clear")) {
                    assertThat(delivered).isEqualTo(raw);
                }
                assertThat(sentTexts).hasSize(structured ? 3 : 1).doesNotContain("Alice");
                assertThat(inspected).extracting(ContentSegment::text).containsExactlyElementsOf(sentTexts);
                assertThat(inspected).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                        .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
                if (structured) {
                    assertThat(sentTexts).doesNotContain("attack").endsWith("123", "done");
                } else {
                    assertThat(sentTexts).containsExactly(delivered);
                }
                assertThat(privacy.activeSessionCount()).isZero();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void absentOrInputOnlyPrivacyCannotAuthorizeOutputDisclosure(boolean streaming) {
        for (boolean inputPrivacy : List.of(false, true)) {
            PrivacyService privacy = privacyService(new CopyOnWriteArrayList<>());
            ChatClient.Builder builder = ChatClient.builder(model("Alice"));
            if (inputPrivacy) {
                new PrivacyChatClientConfigurer(privacy).configure(builder);
            }
            ChatClient client = builder.defaultAdvisors(output).build();
            assertDisclosureDenied(client, streaming);
            assertThat(privacy.activeSessionCount()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"isThought", "thinking"})
    void thoughtAndAnswerAreSeparateCompletedTextUnits(String thoughtMarker) {
        List<String> analyzed = new CopyOnWriteArrayList<>();
        PrivacyService privacy = privacyService(analyzed);
        ChatResponse thought = new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("Ali").properties(Map.of(thoughtMarker, true)).build())));
        ChatResponse answer = response("ce");
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { return answer; }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(thought, answer); }
        };
        ChatClient client = PrivacyChatClientConfigurer.builder(privacy)
                .outputProtection(PrivacyOutputAction.TOKENIZE, "blocked").build()
                .configure(ChatClient.builder(model)).defaultAdvisors(output).build();
        List<ChatClientResponse> frames = client.prompt().user("hello").stream()
                .chatClientResponse().collectList().block(WAIT);
        assertThat(frames).hasSize(2).allSatisfy(frame -> assertThat(frame.context()).isEmpty());
        assertThat(frames.get(0).chatResponse().getResult().getOutput().getMetadata())
                .containsEntry(thoughtMarker, true);
        assertThat(sentTexts).containsExactly("Ali", "ce");
        assertThat(inspected).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
        assertThat(analyzed).contains("Ali", "ce").doesNotContain("Alice");
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @Test
    void reorderedChoicesRetainTheirOwnCompletedText() {
        PrivacyService privacy = privacyService(new CopyOnWriteArrayList<>());
        ChatResponse first = new ChatResponse(List.of(indexed(0, "Ali"), indexed(1, "cl")));
        ChatResponse last = new ChatResponse(List.of(indexed(1, "ear"), indexed(0, "ce")));
        ChatModel model = new ChatModel() {
            public ChatResponse call(Prompt prompt) { return first; }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(first, last); }
        };
        ChatClient client = PrivacyChatClientConfigurer.builder(privacy)
                .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                .configure(ChatClient.builder(model)).defaultAdvisors(output).build();
        client.prompt().user("hello").stream().chatResponse().collectList().block(WAIT);
        assertThat(sentTexts).hasSize(2).contains("clear").doesNotContain("Alice");
        assertThat(inspected).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnDirectProtectionCompletesBeforeHttpInspection(boolean streaming) {
        PrivacyService privacy = privacyService(new CopyOnWriteArrayList<>());
        ToolCallback tool = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("fixture").inputSchema("{}").build();
            }
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(true).build();
            }
            public String call(String arguments) { return "Hello Alice"; }
        };
        ToolCallback wrapped = new PrivacyToolCallbackFactory(privacy, ToolDisclosurePolicy.denyAll()).wrap(tool);
        ChatModel model = new ChatModel() {
            public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "lookup", "{}"))).build())));
            }
            public Flux<ChatResponse> stream(Prompt prompt) { return Flux.just(call(prompt)); }
        };
        ChatClient client = PrivacyChatClientConfigurer.builder(privacy)
                .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                .configure(ChatClient.builder(model)).defaultAdvisors(output).defaultTools(wrapped).build();
        String delivered = invoke(client, streaming);
        assertThat(delivered).startsWith("Hello ").doesNotContain("Alice");
        assertThat(sentTexts).containsExactly(delivered);
        assertThat(inspected).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED));
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "true,true"})
    void replacedOrConcatenatedCompletedOutputCannotReuseItsStatus(boolean streaming, boolean duplicateFrames) {
        PrivacyService privacy = privacyService(new CopyOnWriteArrayList<>());
        ChatClient client = PrivacyChatClientConfigurer.builder(privacy)
                .outputProtection(PrivacyOutputAction.TOKENIZE, "blocked").build()
                .configure(ChatClient.builder(model("clear")))
                .defaultAdvisors(output, new ResponseMutation(duplicateFrames)).build();
        assertDisclosureDenied(client, streaming);
        assertThat(privacy.activeSessionCount()).isZero();
    }

    private void assertDisclosureDenied(ChatClient client, boolean streaming) {
        assertThatThrownBy(() -> invoke(client, streaming)).isInstanceOf(InspectionException.class)
                .satisfies(error -> assertThat(((InspectionException) error).failure())
                        .isEqualTo(InspectionFailureCode.DISCLOSURE_DENIED));
        assertThat(sentTexts).isEmpty();
        assertThat(inspected).isEmpty();
    }

    private PrivacyService privacyService(List<String> analyzed) {
        return new PrivacyService(List.of((text, options, limits) -> {
            analyzed.add(text);
            int at = text.indexOf("Alice");
            return at < 0 ? List.of() : List.of(new PiiSpan("PERSON", at, at + 5, 1.0));
        }), PiiAnalysisOptions.defaults());
    }

    private ChatModel model(String raw) {
        return new ChatModel() {
            public ChatResponse call(Prompt prompt) { return response(raw); }
            public Flux<ChatResponse> stream(Prompt prompt) {
                int middle = raw.length() / 2;
                return Flux.just(response(raw.substring(0, middle)), response(raw.substring(middle)));
            }
        };
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private Generation indexed(int index, String text) {
        return new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder().metadata("index", index).build());
    }

    private String invoke(ChatClient client, boolean streaming) {
        if (streaming) {
            List<ChatClientResponse> frames = client.prompt().user("hello").stream()
                    .chatClientResponse().collectList().block(WAIT);
            assertThat(frames).allSatisfy(frame -> assertThat(frame.context()).isEmpty());
            return frames.stream().map(frame -> frame.chatResponse().getResult().getOutput().getText())
                    .filter(text -> text != null).collect(Collectors.joining());
        }
        ChatClientResponse response = client.prompt().user("hello").call().chatClientResponse();
        assertThat(response.context()).isEmpty();
        return response.chatResponse().getResult().getOutput().getText();
    }

    /** Runs between the Privacy lifecycle and final output inspection. */
    private final class ResponseMutation implements CallAdvisor, StreamAdvisor, PriorityOrdered {
        private final boolean duplicateFrames;

        private ResponseMutation(boolean duplicateFrames) {
            this.duplicateFrames = duplicateFrames;
        }

        public String getName() { return "ResponseMutation"; }
        public int getOrder() { return InspectionOutputAdvisor.DEFAULT_ORDER + 1; }
        public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
            return replace(chain.nextCall(request));
        }
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
            Flux<ChatClientResponse> responses = chain.nextStream(request);
            if (duplicateFrames) {
                return responses.concatMap(response -> Flux.just(response, response));
            }
            return responses.map(this::replace);
        }
        private ChatClientResponse replace(ChatClientResponse original) {
            return original.mutate().chatResponse(response("new unprocessed text")).build();
        }
    }
}
