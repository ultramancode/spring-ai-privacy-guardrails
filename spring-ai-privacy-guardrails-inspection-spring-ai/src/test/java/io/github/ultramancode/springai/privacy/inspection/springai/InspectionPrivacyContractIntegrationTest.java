package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionPrivacyContractIntegrationTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void privacyReusesScalarAnalysisWhileInspectionRunsOnEveryModelRequest(boolean streaming) {
        List<String> privacyAnalyzedTexts = new CopyOnWriteArrayList<>();
        PrivacyProcessingLimits privacyLimits = PrivacyProcessingLimits.builder()
                .maxTextCharacters(512).maxAnalysisSegments(1).maxResultSpans(1).build();
        PiiAnalyzer analyzer = (text, options, limits) -> {
            assertThat(limits).isSameAs(privacyLimits);
            privacyAnalyzedTexts.add(text);
            return text.equals("Alice") ? List.of(new PiiSpan("PERSON", 0, 5, 1.0)) : List.of();
        };
        PrivacyService privacyService = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), privacyLimits);
        String jsonPayload = "{\"key@example.com\":\"Alice\",\"repeat\":\"Alice\",\"repeat\":42,\"blank\":\" \"}";
        ToolCallback lookupToolCallback = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("static-contact@example.com")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"schema@example.com\":{\"type\":\"string\"}}}")
                        .build();
            }

            public String call(String arguments) {
                return jsonPayload;
            }
        };
        List<InspectionRequest> inspectionRequests = new CopyOnWriteArrayList<>();
        ContentInspector inspector = new ContentInspector() {
            @Override
            public String inspectorId() {
                return "privacy-scope";
            }

            @Override
            public boolean requiresPrivacyProcessedContent() {
                return true;
            }

            @Override
            public InspectionResult inspect(InspectionRequest request) {
                inspectionRequests.add(request);
                assertThat(request.segments()).allSatisfy(segment -> {
                    assertThat(segment.privacyProcessingStatus()).isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED);
                    assertThat(segment.text()).doesNotContain("Alice", "static-contact", "schema@example.com");
                });
                return InspectionResult.completed(request.segments().stream()
                        .map(ContentSegment::id).collect(Collectors.toSet()), List.of());
            }
        };
        AtomicInteger modelCallCount = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                modelCallCount.incrementAndGet();
                if (prompt.getInstructions().stream().noneMatch(ToolResponseMessage.class::isInstance)) {
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}")))
                            .build())));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> Flux.just(call(prompt)));
            }
        };
        InspectionChatClientConfigurer inspectionChatClientConfigurer =
                inspectionChatClientConfigurer(inspector, InspectionLimits.defaults());
        ChatClient client = ModelRequestBoundaryConfigurer.compose(inspectionChatClientConfigurer, new PrivacyChatClientConfigurer(privacyService))
                .configure(ChatClient.builder(model))
                .defaultTools(new PrivacyToolCallbackFactory(privacyService, ToolDisclosurePolicy.denyAll()).wrap(lookupToolCallback)).build();

        for (int invocation = 0; invocation < 2; invocation++) {
            invoke(client, jsonPayload, streaming);
            assertThat(privacyService.activeSessionCount()).isZero();
        }

        assertThat(modelCallCount).hasValue(4);
        assertThat(inspectionRequests).hasSize(4);
        assertThat(privacyAnalyzedTexts).containsExactly("Alice", "42", "Alice", "42");
        for (InspectionRequest request : inspectionRequests) {
            String protectedJson = request.segments().get(0).text();
            assertThat(protectedJson).doesNotContain("Alice");
            assertThat(protectedJson).startsWith("{\"key@example.com\":\"");
            assertThat(protectedJson.split("\"repeat\":", -1)).hasSize(3);
            assertThat(protectedJson).endsWith("\"repeat\":42,\"blank\":\" \"}");
        }
        assertThat(inspectionRequests.get(1).segments()).extracting(ContentSegment::role)
                .containsExactly(ContentSegment.Role.USER, ContentSegment.Role.ASSISTANT,
                        ContentSegment.Role.ASSISTANT, ContentSegment.Role.TOOL);
        assertThat(inspectionRequests.get(1).segments().get(3).text()).isEqualTo(inspectionRequests.get(0).segments().get(0).text());
        assertThat(inspectionRequests.get(2).segments().get(0).text()).isNotEqualTo(inspectionRequests.get(0).segments().get(0).text());
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void privacyAndInspectionEnforceIndependentLimitsAndCleanUp(boolean streaming, boolean exceedsPrivacyLimit) {
        PiiAnalyzer analyzer = (text, options, limits) -> List.of();
        PrivacyService privacyService = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(),
                PrivacyProcessingLimits.builder().maxTextCharacters(exceedsPrivacyLimit ? 4 : 64).build());
        AtomicInteger inspectionCount = new AtomicInteger();
        ContentInspector inspector = new ContentInspector() {
            @Override
            public String inspectorId() {
                return "inspection-limits";
            }

            @Override
            public boolean requiresPrivacyProcessedContent() {
                return true;
            }

            @Override
            public InspectionResult inspect(InspectionRequest request) {
                inspectionCount.incrementAndGet();
                return InspectionResult.completed(request.segments().stream()
                        .map(ContentSegment::id).collect(Collectors.toSet()), List.of());
            }
        };
        InspectionChatClientIntegrationTest.RecordingModel model = new InspectionChatClientIntegrationTest.RecordingModel();
        InspectionLimits inspectionLimits = new InspectionLimits(4, exceedsPrivacyLimit ? 64 : 4, Duration.ofSeconds(10));
        ChatClient client = ModelRequestBoundaryConfigurer.compose(new PrivacyChatClientConfigurer(privacyService),
                inspectionChatClientConfigurer(inspector, inspectionLimits)).configure(ChatClient.builder(model)).build();

        assertThatThrownBy(() -> invoke(client, "hello", streaming))
                .isInstanceOf(exceedsPrivacyLimit ? PrivacyGuardrailException.class : InspectionException.class);
        assertThat(inspectionCount).hasValue(0);
        assertThat(model.modelCallCount).hasValue(0);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedMessagesCannotReusePrivacyProvenance(boolean streaming) {
        PrivacyService privacyService = new PrivacyService(List.of((text, options, limits) -> List.of()),
                PiiAnalysisOptions.defaults());
        AtomicInteger inspectionCount = new AtomicInteger();
        ContentInspector inspector = new ContentInspector() {
            @Override
            public String inspectorId() {
                return "privacy-provenance";
            }

            @Override
            public boolean requiresPrivacyProcessedContent() {
                return true;
            }

            @Override
            public InspectionResult inspect(InspectionRequest request) {
                inspectionCount.incrementAndGet();
                return InspectionResult.completed(request.segments().stream()
                        .map(ContentSegment::id).collect(Collectors.toSet()), List.of());
            }
        };
        ModelRequestBoundaryConfigurer messageChangingConfigurer = (builder, boundary) -> boundary.authorization(request ->
                request.mutate().prompt(new Prompt(List.of(new UserMessage("changed")), request.prompt().getOptions())).build());
        InspectionChatClientIntegrationTest.RecordingModel model = new InspectionChatClientIntegrationTest.RecordingModel();
        ChatClient client = ModelRequestBoundaryConfigurer.compose(new PrivacyChatClientConfigurer(privacyService),
                messageChangingConfigurer, inspectionChatClientConfigurer(inspector, InspectionLimits.defaults()))
                .configure(ChatClient.builder(model)).build();

        assertThatThrownBy(() -> invoke(client, "hello", streaming)).hasMessageContaining("PRIVACY_PROCESSING_REQUIRED");
        assertThat(inspectionCount).hasValue(0);
        assertThat(model.modelCallCount).hasValue(0);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    private InspectionChatClientConfigurer inspectionChatClientConfigurer(ContentInspector inspector, InspectionLimits limits) {
        return new InspectionChatClientConfigurer(new InspectionService(List.of(inspector)), limits,
                request -> PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(request)
                        ? ContentSegment.PrivacyProcessingStatus.PROCESSED : ContentSegment.PrivacyProcessingStatus.UNKNOWN,
                InspectionObserver.noop());
    }

    private void invoke(ChatClient client, String text, boolean streaming) {
        if (streaming) {
            client.prompt().user(text).stream().content().collectList().block(Duration.ofSeconds(10));
        } else {
            client.prompt().user(text).call().content();
        }
    }
}
