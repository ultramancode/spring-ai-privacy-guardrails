package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionTestSupport.RecordingChatModel;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies Privacy processing and input inspection at each model request boundary. */
class InspectionPrivacyContractIntegrationTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void privacyReusesScalarAnalysisWhileInspectionRunsOnEveryModelRequest(boolean streaming) {
        // Repeated values share analysis within one session.
        String userJson = """
                {"key@example.com":"Alice",\
                "repeat":"Alice",\
                "repeat":42,\
                "blank":" "}
                """.strip();
        List<String> privacyAnalyzedTexts = new CopyOnWriteArrayList<>();
        List<InspectionRequest> inspectionRequests = new CopyOnWriteArrayList<>();
        AtomicInteger toolInvocationCount = new AtomicInteger();
        AtomicInteger modelCallCount = new AtomicInteger();
        PrivacyService privacyService = recordingPrivacyService(privacyAnalyzedTexts);
        // The tool returns the same user JSON, including Alice and 42.
        ToolCallback lookupToolCallback = lookupToolReturningSameUserJson(userJson, toolInvocationCount);
        ContentInspector inspector = privacyRequiredRecordingInspector(inspectionRequests);
        ChatModel model = lookupThenDoneModel(modelCallCount);
        InspectionChatClientConfigurer inspectionChatClientConfigurer =
                inspectionChatClientConfigurer(inspector, InspectionLimits.defaults());
        ChatClient client = ModelRequestBoundaryConfigurer.compose(
                        inspectionChatClientConfigurer, new PrivacyChatClientConfigurer(privacyService))
                .configure(ChatClient.builder(model))
                .defaultTools(new PrivacyToolCallbackFactory(privacyService, ToolDisclosurePolicy.denyAll())
                        .wrap(lookupToolCallback))
                .build();

        List<String> protectedUserJsonByInvocation = new ArrayList<>();
        // Reuse one client to verify analysis is reused within a session and refreshed in the next session.
        for (int invocation = 0; invocation < 2; invocation++) {
            int initialRequestIndex = inspectionRequests.size();
            assertThat(invoke(client, userJson, streaming)).isEqualTo("done");
            assertThat(privacyService.activeSessionCount()).isZero();

            // Each chat invocation makes one model request before the tool call and another after it.
            assertThat(inspectionRequests).hasSize(initialRequestIndex + 2);
            assertThat(modelCallCount).hasValue(initialRequestIndex + 2);
            assertThat(toolInvocationCount).hasValue(invocation + 1);
            InspectionRequest requestBeforeToolCall = inspectionRequests.get(initialRequestIndex);
            InspectionRequest requestAfterToolCall = inspectionRequests.get(initialRequestIndex + 1);
            assertInspectionBeforeAndAfterToolCall(requestBeforeToolCall, requestAfterToolCall);
            protectedUserJsonByInvocation.add(requestBeforeToolCall.segments().get(0).text());
        }

        // Each session analyzes Alice and 42 once. The tool result reuses that analysis.
        // JSON keys and tool metadata are outside the analysis scope.
        assertThat(privacyAnalyzedTexts).containsExactly("Alice", "42", "Alice", "42");
        assertThat(protectedUserJsonByInvocation).doesNotHaveDuplicates();
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void privacyAndInspectionEnforceIndependentLimitsAndCleanUp(boolean streaming, boolean exceedsPrivacyLimit) {
        String inputText = "hello";
        int inputLength = inputText.length();
        int privacyCharacterLimit;
        int inspectionCharacterLimit;
        if (exceedsPrivacyLimit) {
            privacyCharacterLimit = inputLength - 1;
            inspectionCharacterLimit = inputLength + 1;
        } else {
            privacyCharacterLimit = inputLength + 1;
            inspectionCharacterLimit = inputLength - 1;
        }
        PiiAnalyzer analyzer = (text, options, limits) -> List.of();
        PrivacyService privacyService = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(),
                PrivacyProcessingLimits.builder().maxTextCharacters(privacyCharacterLimit).build());
        AtomicInteger inspectorInvocationCount = new AtomicInteger();
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
                inspectorInvocationCount.incrementAndGet();
                return InspectionResult.completed(request.segments().stream()
                        .map(ContentSegment::id).collect(Collectors.toSet()), List.of());
            }
        };
        RecordingChatModel model = new RecordingChatModel();
        InspectionLimits inspectionLimits = new InspectionLimits(4, inspectionCharacterLimit, TEST_TIMEOUT);
        ChatClient client = ModelRequestBoundaryConfigurer.compose(new PrivacyChatClientConfigurer(privacyService),
                inspectionChatClientConfigurer(inspector, inspectionLimits)).configure(ChatClient.builder(model)).build();

        if (exceedsPrivacyLimit) {
            assertThatThrownBy(() -> invoke(client, inputText, streaming))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure ->
                            assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED));
        } else {
            assertThatThrownBy(() -> invoke(client, inputText, streaming))
                    .isInstanceOfSatisfying(InspectionException.class, failure ->
                            assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED));
        }
        assertThat(inspectorInvocationCount).hasValue(0);
        assertThat(model.modelCallCount).hasValue(0);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void changedMessagesCannotReusePrivacyProvenance(boolean streaming) {
        PrivacyService privacyService = new PrivacyService(List.of((text, options, limits) -> List.of()),
                PiiAnalysisOptions.defaults());
        AtomicInteger inspectorInvocationCount = new AtomicInteger();
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
                inspectorInvocationCount.incrementAndGet();
                return InspectionResult.completed(request.segments().stream()
                        .map(ContentSegment::id).collect(Collectors.toSet()), List.of());
            }
        };
        ModelRequestBoundaryConfigurer messageChangingConfigurer = replaceUserMessageAfterPrivacy("changed");
        RecordingChatModel model = new RecordingChatModel();
        ChatClient client = ModelRequestBoundaryConfigurer.compose(new PrivacyChatClientConfigurer(privacyService),
                messageChangingConfigurer, inspectionChatClientConfigurer(inspector, InspectionLimits.defaults()))
                .configure(ChatClient.builder(model)).build();

        assertThatThrownBy(() -> invoke(client, "hello", streaming))
                .isInstanceOfSatisfying(InspectionException.class, failure ->
                        assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.PRIVACY_PROCESSING_REQUIRED));
        assertThat(inspectorInvocationCount).hasValue(0);
        assertThat(model.modelCallCount).hasValue(0);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    private PrivacyService recordingPrivacyService(List<String> privacyAnalyzedTexts) {
        PrivacyProcessingLimits privacyLimits = PrivacyProcessingLimits.builder()
                .maxTextCharacters(512).maxAnalysisSegments(1).maxResultSpans(1).build();
        PiiAnalyzer analyzer = (text, options, limits) -> {
            privacyAnalyzedTexts.add(text);
            return text.equals("Alice") ? List.of(new PiiSpan("PERSON", 0, 5, 1.0)) : List.of();
        };
        return new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults(), privacyLimits);
    }

    private ToolCallback lookupToolReturningSameUserJson(String userJson, AtomicInteger toolInvocationCount) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("static-contact@example.com")
                        .inputSchema("""
                                {
                                  "type": "object",
                                  "properties": {
                                    "schema@example.com": {
                                      "type": "string"
                                    }
                                  }
                                }
                                """)
                        .build();
            }

            public String call(String arguments) {
                toolInvocationCount.incrementAndGet();
                return userJson;
            }
        };
    }

    private ContentInspector privacyRequiredRecordingInspector(List<InspectionRequest> inspectionRequests) {
        return new ContentInspector() {
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
    }

    private ChatModel lookupThenDoneModel(AtomicInteger modelCallCount) {
        return new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                modelCallCount.incrementAndGet();
                // Request lookup first. Return done once its result appears in the conversation.
                if (prompt.getInstructions().stream().noneMatch(ToolResponseMessage.class::isInstance)) {
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", """
                                    {}
                                    """.strip())))
                            .build())));
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> Flux.just(call(prompt)));
            }
        };
    }

    private void assertInspectionBeforeAndAfterToolCall(InspectionRequest requestBeforeToolCall,
                                                       InspectionRequest requestAfterToolCall) {
        assertThat(requestBeforeToolCall.segments()).extracting(ContentSegment::role)
                .containsExactly(ContentSegment.Role.USER);
        List<ContentSegment> segmentsAfterToolCall = requestAfterToolCall.segments();
        assertThat(segmentsAfterToolCall).extracting(ContentSegment::role)
                .containsExactly(ContentSegment.Role.USER, ContentSegment.Role.ASSISTANT,
                        ContentSegment.Role.ASSISTANT, ContentSegment.Role.TOOL);
        String protectedUserJson = requestBeforeToolCall.segments().get(0).text();
        assertThat(segmentsAfterToolCall.get(0).text()).isEqualTo(protectedUserJson);
        assertThat(segmentsAfterToolCall.get(segmentsAfterToolCall.size() - 1).text()).isEqualTo(protectedUserJson);
    }

    private ModelRequestBoundaryConfigurer replaceUserMessageAfterPrivacy(String replacementText) {
        // The authorization stage runs between Privacy and Inspection.
        // Use it to simulate a message change after Privacy processing.
        return (builder, boundary) -> boundary.authorization(request -> {
            Prompt replacementPrompt = new Prompt(List.of(new UserMessage(replacementText)), request.prompt().getOptions());
            return request.mutate().prompt(replacementPrompt).build();
        });
    }

    private InspectionChatClientConfigurer inspectionChatClientConfigurer(ContentInspector inspector, InspectionLimits limits) {
        // Only the marker matching the current model input establishes completed Privacy processing.
        return new InspectionChatClientConfigurer(new InspectionService(List.of(inspector)), limits,
                request -> PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(request)
                        ? ContentSegment.PrivacyProcessingStatus.PROCESSED : ContentSegment.PrivacyProcessingStatus.UNKNOWN,
                InspectionObserver.noop());
    }

    private String invoke(ChatClient client, String text, boolean streaming) {
        if (streaming) {
            return client.prompt().user(text).stream().content().collect(Collectors.joining()).block(TEST_TIMEOUT);
        }
        return client.prompt().user(text).call().content();
    }
}
