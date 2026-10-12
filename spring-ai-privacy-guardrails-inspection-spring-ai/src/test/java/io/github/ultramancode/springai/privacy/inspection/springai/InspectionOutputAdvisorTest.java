package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionPolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAction;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputBlockedException;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.DefaultAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.ToolAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.github.ultramancode.springai.privacy.inspection.springai.InspectionRuntimeScopeTest.attackBlockingService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

class InspectionOutputAdvisorTest {
    private static final Duration WAIT = Duration.ofSeconds(5);

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private ChatModel model(ChatResponse call, Flux<ChatResponse> stream) {
        return new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                return call;
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return stream;
            }
        };
    }

    private InspectionOutputAdvisor advisor() {
        return new InspectionOutputAdvisor(attackBlockingService());
    }

    private ChatClient client(ChatModel model, InspectionOutputAdvisor output) {
        return new InspectionChatClientConfigurer(attackBlockingService()).withOutputInspection(output)
                .configure(ChatClient.builder(model)).build();
    }

    private Generation indexed(int index, String text) {
        return new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder().metadata("index", index).build());
    }

    private InspectionOutputAdvisor recordingAdvisor(List<String> captured) {
        ContentInspector recorder = new ContentInspector() {
            public String inspectorId() {
                return "record-output";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                request.segments().forEach(s -> captured.add(s.text()));
                return InspectionResult.completed(request.segments().stream().map(ContentSegment::id)
                        .collect(Collectors.toSet()), List.of());
            }
        };
        InspectionRule rule = InspectionRule.literal("attack", InspectionFinding.Category.PROMPT_ATTACK, "attack");
        ContentInspector ruleInspector = new RuleBasedContentInspector("rules", List.of(rule));
        InspectionService service = new InspectionService(List.of(recorder, ruleInspector));
        return new InspectionOutputAdvisor(service);
    }

    private ChatModel toolModel(AtomicInteger rounds, String intermediateText, String finalText, int toolRounds) {
        return new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                if (rounds.getAndIncrement() < toolRounds) {
                    return toolCall(intermediateText);
                }
                return response(finalText);
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    if (rounds.getAndIncrement() < toolRounds) {
                        return Flux.just(response(intermediateText), toolCall(""));
                    }
                    return Flux.just(response(finalText.substring(0, 2)), response(finalText.substring(2)));
                });
            }
        };
    }

    private ChatResponse toolCall(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "lookup",
                        "{\"q\":\"attack argument\"}"))).build())));
    }

    private ToolCallback tool(boolean direct, String result, AtomicInteger calls) {
        return tool("lookup", direct, result, calls);
    }

    private ToolCallback tool(String name, boolean direct, String result, AtomicInteger calls) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("fixture").inputSchema("{}").build();
            }

            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(direct).build();
            }

            public String call(String arguments) {
                calls.incrementAndGet();
                return result;
            }
        };
    }

    private void assertBlocked(ChatClient client, boolean streaming) {
        if (streaming) {
            StepVerifier.create(client.prompt().user("hello").stream().content())
                    .expectError(InspectionBlockedException.class).verify(WAIT);
            return;
        }
        assertThatThrownBy(() -> invoke(client, streaming)).isInstanceOf(InspectionBlockedException.class);
    }

    private String invoke(ChatClient client, boolean streaming) {
        if (streaming) {
            return client.prompt().user("hello").stream().content().collectList()
                    .map(parts -> String.join("", parts)).block(WAIT);
        }
        return client.prompt().user("hello").call().content();
    }

    @Test
    void outputOnlyRegistrationNeedsNoInputInspectionOrPrivacyConfigurer() {
        ChatClient client = ChatClient.builder(model(response("safe"), Flux.just(response("safe"))))
                .defaultAdvisors(advisor()).build();
        assertThat(client.prompt().user("attack").call().content()).isEqualTo("safe");
        StepVerifier.create(client.prompt().user("attack").stream().content()).expectNext("safe").verifyComplete();
    }

    @ParameterizedTest
    @ValueSource(strings = {"safe", "attack"})
    void inspectsResponsesWithoutBuiltInModelAdvisors(String text) {
        ChatClientResponse response = ChatClientResponse.builder().chatResponse(response(text)).build();
        class ResponseAdvisor implements CallAdvisor, StreamAdvisor {
            public String getName() {
                return "application-response";
            }

            public int getOrder() {
                return 100;
            }

            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                return response;
            }

            public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
                return Flux.just(response);
            }
        }
        DefaultAroundAdvisorChain chain = DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .pushAll(List.of(advisor(), new ResponseAdvisor())).build();
        ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt("hello")).build();
        if (text.equals("attack")) {
            assertThatThrownBy(() -> chain.nextCall(request)).isInstanceOf(InspectionBlockedException.class);
            StepVerifier.create(chain.nextStream(request))
                    .expectError(InspectionBlockedException.class).verify(WAIT);
        } else {
            assertThat(chain.nextCall(request)).isSameAs(response);
            StepVerifier.create(chain.nextStream(request)).expectNext(response).verifyComplete();
        }
    }

    @Test
    void outputIsOffByDefaultAndOptInBlocksBeforeAnyStreamFrame() {
        ChatModel model = model(response("attack"), Flux.just(response("at"), response("tack")));
        ChatClient inputOnly = new InspectionChatClientConfigurer(attackBlockingService())
                .configure(ChatClient.builder(model)).build();
        assertThat(inputOnly.prompt().user("safe").call().content()).isEqualTo("attack");
        assertThatThrownBy(() -> client(model, advisor()).prompt().user("safe").call().content())
                .isInstanceOf(InspectionBlockedException.class);
        StepVerifier.create(client(model, advisor()).prompt().user("safe").stream().content())
                .expectError(InspectionBlockedException.class).verify(WAIT);
    }

    @Test
    void allowedResponsesAndFramesArePreservedAndRequestsAreIndependent() {
        ChatResponse first = response("safe ");
        ChatResponse second = response("response");
        ChatModel model = model(first, Flux.just(first, second));
        ChatClient client = client(model, advisor());
        assertThat(client.prompt().user("hello").call().chatResponse()).isSameAs(first);
        for (int i = 0; i < 2; i++) {
            StepVerifier.create(client.prompt().user("hello").stream().chatResponse())
                    .assertNext(actual -> assertThat(actual).isSameAs(first))
                    .assertNext(actual -> assertThat(actual).isSameAs(second)).verifyComplete();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"safe answer", "attack"})
    void streamingInspectsCombinedTextAcrossSupportedMessageTypes(String text) {
        ChatResponse first = new ChatResponse(List.of(new Generation(
                DeepSeekAssistantMessage.builder().content(text.substring(0, 2)).build())));
        ChatResponse second = response(text.substring(2));
        List<String> inspected = new CopyOnWriteArrayList<>();
        ChatClient client = ChatClient.builder(model(second, Flux.just(first, second)))
                .defaultAdvisors(recordingAdvisor(inspected)).build();
        Flux<ChatResponse> responses = client.prompt().user("hello").stream().chatResponse();
        if (text.equals("attack")) {
            StepVerifier.create(responses).expectError(InspectionBlockedException.class).verify(WAIT);
        } else {
            StepVerifier.create(responses)
                    .assertNext(actual -> assertThat(actual).isSameAs(first))
                    .assertNext(actual -> assertThat(actual).isSameAs(second)).verifyComplete();
        }
        assertThat(inspected).containsExactly(text);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputExcludesReasoningMetadataAndToolCommands(boolean streaming) {
        List<Generation> generationsWithExcludedContent = List.of(
                new Generation(DeepSeekAssistantMessage.builder().content("safe").reasoningContent("attack").build()),
                new Generation(AssistantMessage.builder().content("safe").properties(Map.of("thinking", "attack")).build()),
                new Generation(new AssistantMessage("safe"), ChatGenerationMetadata.builder().metadata("reasoningContent", "attack").build()),
                new Generation(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("id", "function", "tool", "{\"q\":\"attack\"}"))).build()));
        for (Generation generation : generationsWithExcludedContent) {
            ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("safe")), generation));
            ChatClient client = client(model(response, Flux.just(response)), advisor());
            assertThat(invoke(client, streaming)).contains("safe");
        }
        ChatResponse unsafeText = new ChatResponse(List.of(new Generation(new AssistantMessage("safe")),
                new Generation(new AssistantMessage("attack"))));
        assertBlocked(client(model(unsafeText, Flux.just(unsafeText)), advisor()), streaming);
    }

    @Test
    void streamingCorrelatesReorderedChoicesAndPreservesSplitJson() {
        ChatResponse first = new ChatResponse(List.of(indexed(0, "safe"), indexed(1, "{\"text\":\"at\\u0074")));
        ChatResponse second = new ChatResponse(List.of(indexed(1, "ack\"}"), indexed(0, " value")));
        List<String> inspected = new CopyOnWriteArrayList<>();
        ChatClient client = client(model(first, Flux.just(first, second)), recordingAdvisor(inspected));
        StepVerifier.create(client.prompt().user("hello").stream().chatResponse())
                .assertNext(actual -> assertThat(actual).isSameAs(first))
                .assertNext(actual -> assertThat(actual).isSameAs(second)).verifyComplete();
        assertThat(inspected).containsExactly("safe value", "{\"text\":\"at\\u0074ack\"}");

        ChatResponse ambiguous = new ChatResponse(List.of(new Generation(new AssistantMessage("tail"))));
        StepVerifier.create(client(model(first, Flux.just(first, ambiguous)), advisor()).prompt().user("hello").stream().content())
                .expectErrorMatches(e -> e instanceof InspectionException f && f.failureCode() == InspectionFailureCode.UNSUPPORTED_CONTENT)
                .verify(WAIT);
    }

    @Test
    void splitReasoningAndOpaqueMetadataAreExcluded() {
        ChatResponse first = new ChatResponse(List.of(new Generation(DeepSeekAssistantMessage.builder().content("").reasoningContent("at").build())));
        ChatResponse second = new ChatResponse(List.of(new Generation(DeepSeekAssistantMessage.builder().content("").reasoningContent("tack").build())));
        StepVerifier.create(client(model(first, Flux.just(first, second)), advisor()).prompt().user("hello").stream().chatResponse())
                .expectNextCount(2).verifyComplete();
        ChatResponse safe = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("{\"answer\":\"safe\"}")
                .properties(Map.of("signature", "attack", "thinking", Map.of("opaque", "attack"))).build())));
        assertThat(client(model(safe, Flux.just(safe)), advisor()).prompt().user("hello").call().chatResponse()).isSameAs(safe);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputRulesCanMatchJsonKeysAndStructure(boolean streaming) {
        InspectionRule rule = InspectionRule.regex("action", InspectionFinding.Category.PROMPT_ATTACK,
                "^\\{\\s*\"action\"\\s*:\\s*\"attack\"\\s*\\}$");
        InspectionService service = new InspectionService(List.of(new RuleBasedContentInspector("rules", List.of(rule))));
        InspectionOutputAdvisor output = new InspectionOutputAdvisor(service);
        String allowedJson = "{\"note\":\"attack\"}";
        ChatResponse allowed = response(allowedJson);
        assertThat(invoke(client(model(allowed, Flux.just(allowed)), output), streaming)).isEqualTo(allowedJson);

        ChatResponse blocked = response("{\n  \"action\": \"attack\"\n}");
        assertBlocked(client(model(blocked, Flux.just(blocked)), output), streaming);
    }

    @Test
    void outputDoesNotRejectUninspectedToolArgumentFragments() {
        ChatResponse partial = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "lookup", "{\"q\":\"at"))).build())));
        StepVerifier.create(client(model(partial, Flux.just(partial)), advisor()).prompt().user("hello").stream().chatResponse())
                .expectNext(partial).verifyComplete();
    }

    @Test
    void bufferingEnforcesCharacterSegmentAndFrameBudgetsAndNotifiesOnce() {
        List<InspectionLimits> restrictiveLimits = List.of(
                new InspectionLimits(2, 3, WAIT), new InspectionLimits(1, 100, WAIT));

        for (InspectionLimits limits : restrictiveLimits) {
            AtomicInteger failures = new AtomicInteger();
            InspectionObserver observer = new InspectionObserver() {
                public void onInspection(InspectionReport report) {
                    fail("No inspection before assembly succeeds");
                }

                public void onFailure(InspectionException failure) {
                    failures.incrementAndGet();
                }
            };
            InspectionOutputAdvisor output = new InspectionOutputAdvisor(attackBlockingService(), limits, observer, 10, WAIT);
            ChatResponse excess = new ChatResponse(List.of(indexed(0, "safe"), indexed(1, "x")));
            StepVerifier.create(client(model(excess, Flux.just(excess)), output).prompt().user("hello").stream().content())
                    .expectErrorMatches(e -> e instanceof InspectionException f && f.failureCode() == InspectionFailureCode.LIMIT_EXCEEDED)
                    .verify(WAIT);
            assertThat(failures).hasValue(1);
        }
        InspectionOutputAdvisor output = new InspectionOutputAdvisor(attackBlockingService(), InspectionLimits.defaults(),
                InspectionObserver.noop(), 2, WAIT);
        StepVerifier.create(client(model(response(""), Flux.just(response(""), response(""), response(""))), output)
                        .prompt().user("hello").stream().chatResponse())
                .expectErrorMatches(e -> e instanceof InspectionException f && f.failureCode() == InspectionFailureCode.LIMIT_EXCEEDED)
                .verify(WAIT);
    }

    @Test
    void totalStreamDeadlineAndCancellationNeverReleaseBufferedContent() {
        AtomicInteger cancelled = new AtomicInteger();
        InspectionOutputAdvisor output = new InspectionOutputAdvisor(attackBlockingService(), InspectionLimits.defaults(),
                InspectionObserver.noop(), 100, Duration.ofMillis(150));
        Flux<ChatResponse> ongoing = Flux.interval(Duration.ofMillis(20)).map(i -> response("safe"))
                .doOnCancel(cancelled::incrementAndGet);
        StepVerifier.create(client(model(response("safe"), ongoing), output).prompt().user("hello").stream().content())
                .expectErrorMatches(e -> e instanceof InspectionException f && f.failureCode() == InspectionFailureCode.TIMEOUT)
                .verify(WAIT);
        assertThat(cancelled).hasValue(1);
        StepVerifier.create(client(model(response("safe"), Flux.just(response("safe")).concatWith(Flux.never())
                        .doOnCancel(cancelled::incrementAndGet)), advisor()).prompt().user("hello").stream().content())
                .thenAwait(Duration.ofMillis(100)).thenCancel().verify(WAIT);
        assertThat(cancelled).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputBodiesShareTheSegmentBudget(boolean streaming) {
        String json = "{\"first\":\"safe\",\"second\":\"at\\u0074ack\",\"enabled\":true,\"missing\":null}";
        InspectionLimits limits = new InspectionLimits(1, json.length(), WAIT);
        InspectionOutputAdvisor output = new InspectionOutputAdvisor(attackBlockingService(), limits,
                InspectionObserver.noop(), 10, WAIT);
        ChatResponse singleBody = response(json);
        ChatClient allowed = ChatClient.builder(model(singleBody, Flux.just(singleBody))).defaultAdvisors(output).build();
        assertThat(invoke(allowed, streaming)).isEqualTo(json);

        ChatResponse multipleBodies = new ChatResponse(List.of(indexed(0, "safe"), indexed(1, "safe")));
        ChatClient blocked = ChatClient.builder(model(multipleBodies, Flux.just(multipleBodies)))
                .defaultAdvisors(output).build();
        assertThatThrownBy(() -> invoke(blocked, streaming)).isInstanceOf(InspectionException.class)
                .satisfies(error -> assertThat(((InspectionException) error).failureCode())
                        .isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED));
    }

    @Test
    void upstreamErrorIsPreservedWithoutReleasingEarlierFrames() {
        IllegalStateException error = new IllegalStateException("fixture model failure");
        StepVerifier.create(client(model(response("safe"), Flux.just(response("safe")).concatWith(Flux.error(error))), advisor())
                        .prompt().user("hello").stream().content())
                .expectErrorMatches(actual -> actual == error).verify(WAIT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputStatusNeverInheritsInputPrivacyAndSessionsCloseOnBlock(boolean streaming) {
        List<ContentSegment> captured = new CopyOnWriteArrayList<>();
        ContentInspector inspector = new ContentInspector() {
            public String inspectorId() {
                return "output-status";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                captured.addAll(request.segments());
                request.requirePrivacyProcessed();
                return InspectionResult.completed(request.segments().stream().map(ContentSegment::id)
                        .collect(Collectors.toSet()), List.of());
            }
        };
        PrivacyService privacy = new PrivacyService(List.of((text, options, limits) -> List.of()), PiiAnalysisOptions.defaults());
        InspectionChatClientConfigurer inspection = new InspectionChatClientConfigurer(attackBlockingService())
                .withOutputInspection(new InspectionOutputAdvisor(new InspectionService(List.of(inspector))));
        ChatClient client = ModelRequestBoundaryConfigurer.compose(new PrivacyChatClientConfigurer(privacy), inspection)
                .configure(ChatClient.builder(model(response("fresh"), Flux.just(response("fresh"))))).build();
        assertThatThrownBy(() -> invoke(client, streaming)).hasMessageContaining("PRIVACY_PROCESSING_REQUIRED");
        assertThat(captured).allSatisfy(s -> assertThat(s.privacyProcessingStatus()).isEqualTo(ContentSegment.PrivacyProcessingStatus.UNKNOWN));
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inspectsAllDeliveredTextAfterToolExecution(boolean streaming) {
        for (String finalText : List.of("safe final", "attack")) {
            AtomicInteger toolCallCount = new AtomicInteger();
            AtomicInteger rounds = new AtomicInteger();
            List<String> inspected = new CopyOnWriteArrayList<>();
            InspectionOutputAdvisor output = recordingAdvisor(inspected);
            ChatClient client = ChatClient.builder(toolModel(rounds, "checking ", finalText, 2))
                    .defaultAdvisors(output).defaultTools(tool(false, "safe tool", toolCallCount)).build();
            String expectedText = finalText;
            if (streaming) {
                expectedText = "checking checking " + finalText;
            }
            if (finalText.equals("attack")) {
                assertBlocked(client, streaming);
            } else {
                assertThat(invoke(client, streaming)).isEqualTo(expectedText);
            }
            assertThat(toolCallCount).hasValue(2);
            assertThat(inspected).containsExactly(expectedText);
        }
    }

    @Test
    void intermediateTextAndTextSpanningToolRoundsCanBlockTheEntireStream() {
        for (String intermediateText : List.of("attack ", "at")) {
            List<String> inspected = new CopyOnWriteArrayList<>();
            AtomicInteger toolCallCount = new AtomicInteger();
            String finalText = "safe final";
            if (intermediateText.equals("at")) {
                finalText = "tack";
            }
            ChatClient client = ChatClient.builder(toolModel(new AtomicInteger(), intermediateText, finalText, 1))
                    .defaultAdvisors(recordingAdvisor(inspected))
                    .defaultTools(tool(false, "safe tool", toolCallCount)).build();
            StepVerifier.create(client.prompt().user("hello").stream().content())
                    .expectError(InspectionBlockedException.class).verify(WAIT);
            assertThat(inspected).containsExactly(intermediateText + finalText);
            assertThat(toolCallCount).hasValue(1);
        }
    }

    @ParameterizedTest
    @CsvSource({"false,1", "true,1", "false,2", "true,2"})
    void inspectsAllDirectToolResultsAndPreservesStreamingText(boolean streaming, int resultCount) {
        for (String direct : List.of("safe direct", "attack")) {
            AtomicInteger toolCallCount = new AtomicInteger();
            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
            List<String> expectedInspected = new ArrayList<>();
            String expectedDelivered = direct;
            if (resultCount == 2) {
                toolCalls.add(new AssistantMessage.ToolCall("first", "function", "firstLookup", "{}"));
                expectedInspected.add("safe first");
                expectedDelivered = "safe first";
            }
            toolCalls.add(new AssistantMessage.ToolCall("last", "function", "lastLookup", "{}"));
            expectedInspected.add(direct);
            if (streaming) {
                expectedInspected.add(0, "checking ");
                expectedDelivered = "checking " + expectedDelivered;
            }
            AssistantMessage toolCallMessage = AssistantMessage.builder().content("").toolCalls(toolCalls).build();
            ChatResponse toolCallResponse = new ChatResponse(List.of(new Generation(toolCallMessage)));
            ChatModel model = new ChatModel() {
                public ChatOptions getOptions() {
                    return ToolCallingChatOptions.builder().build();
                }

                public ChatResponse call(Prompt prompt) {
                    return toolCallResponse;
                }

                public Flux<ChatResponse> stream(Prompt prompt) {
                    return Flux.just(response("checking "), toolCallResponse);
                }
            };
            List<String> inspected = new CopyOnWriteArrayList<>();
            ChatClient client = ChatClient.builder(model)
                    .defaultAdvisors(recordingAdvisor(inspected))
                    .defaultTools(tool("firstLookup", true, "safe first", toolCallCount),
                            tool("lastLookup", true, direct, toolCallCount))
                    .build();
            if (direct.equals("attack")) {
                assertBlocked(client, streaming);
            } else {
                assertThat(invoke(client, streaming)).isEqualTo(expectedDelivered);
            }
            assertThat(toolCallCount).hasValue(resultCount);
            assertThat(inspected).containsExactlyElementsOf(expectedInspected);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inspectsTextAfterPrivacyOutputTransformationInEitherRegistrationOrder(boolean streaming) {
        for (boolean outputFirst : List.of(false, true)) {
            for (PrivacyOutputAction action : List.of(PrivacyOutputAction.REDACT,
                    PrivacyOutputAction.TOKENIZE)) {
                PiiAnalyzer analyzer = (text, options, limits) -> {
                    int at = text.indexOf("attack");
                    return at < 0 ? List.of() : List.of(new PiiSpan(
                            "PERSON", at, at + 6, 0.95));
                };

                PrivacyService privacy = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
                PrivacyChatClientConfigurer configurer = PrivacyChatClientConfigurer.builder(privacy).outputProtection(action, "blocked").build();
                List<String> inspected = new CopyOnWriteArrayList<>();
                InspectionOutputAdvisor output = recordingAdvisor(inspected);
                ChatClient.Builder builder = ChatClient.builder(model(response("attack"), Flux.just(response("at"), response("tack"))));
                if (outputFirst) {
                    builder.defaultAdvisors(output);
                }
                configurer.configure(builder);
                if (!outputFirst) {
                    builder.defaultAdvisors(output);
                }
                String delivered = invoke(builder.build(), streaming);
                assertThat(delivered).doesNotContain("attack");
                assertThat(inspected).containsExactly(delivered);
                assertThat(privacy.activeSessionCount()).isZero();
            }
        }
    }

    @Test
    void privacyOutputProtectionAlsoChecksIntermediateStreamText() {
        PiiAnalyzer analyzer = (text, options, limits) -> {
            int at = text.indexOf("attack intermediate");
            return at < 0 ? List.of() : List.of(new PiiSpan(
                    "PERSON", at, at + "attack intermediate".length(), 0.95));
        };

        PrivacyService privacy = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        PrivacyChatClientConfigurer configurer = PrivacyChatClientConfigurer.builder(privacy)
                .outputProtection(PrivacyOutputAction.BLOCK, "blocked").build();
        AtomicInteger toolCallCount = new AtomicInteger();
        ToolCallback callback = new PrivacyToolCallbackFactory(privacy,
                ToolDisclosurePolicy.denyAll())
                .wrap(tool(false, "safe tool", toolCallCount));
        ChatClient client = configurer.configure(ChatClient.builder(toolModel(new AtomicInteger(), "attack intermediate", "safe final", 2)))
                .defaultAdvisors(advisor()).defaultTools(callback).build();
        StepVerifier.create(client.prompt().user("hello").stream().content())
                .expectError(PrivacyOutputBlockedException.class).verify(WAIT);
        assertThat(toolCallCount).hasValue(2);
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @Test
    void inputOnlyInspectionLeavesStreamedOutputUnchanged() {
        InspectionRule rule = InspectionRule.literal(
                "blocked-output", InspectionFinding.Category.PROMPT_ATTACK, "blocked output");
        ContentInspector inspector = new RuleBasedContentInspector("rules", List.of(rule));
        InspectionService service = new InspectionService(List.of(inspector));
        ChatModel model = toolModel(new AtomicInteger(), "checking ", "blocked output", 1);
        ChatClient client = new InspectionChatClientConfigurer(service).configure(ChatClient.builder(model))
                .defaultTools(tool(false, "safe tool", new AtomicInteger())).build();

        assertThat(invoke(client, true)).isEqualTo("checking blocked output");
        StepVerifier.create(client.prompt().user("blocked output").stream().content())
                .expectError(InspectionBlockedException.class).verify(WAIT);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void outputFrameLimitAppliesAcrossToolRounds(int maxFrames) {
        InspectionOutputAdvisor output = new InspectionOutputAdvisor(attackBlockingService(), InspectionLimits.defaults(),
                InspectionObserver.noop(), maxFrames, WAIT);
        ChatClient client = ChatClient.builder(toolModel(new AtomicInteger(), "checking ", "safe final", 2))
                .defaultAdvisors(output).defaultTools(tool(false, "safe tool", new AtomicInteger())).build();
        Flux<String> content = client.prompt().user("hello").stream().content();
        if (maxFrames == 3) {
            StepVerifier.create(content)
                    .expectErrorMatches(error -> error instanceof InspectionException failure
                            && failure.failureCode() == InspectionFailureCode.LIMIT_EXCEEDED)
                    .verify(WAIT);
        } else {
            StepVerifier.create(content)
                    .expectNext("checking ", "checking ", "sa", "fe final").verifyComplete();
        }
    }

    @Test
    void outputCharacterLimitAppliesAcrossToolRounds() {
        PrivacyService privacy = new PrivacyService(List.of((text, options, limits) -> List.of()),
                PiiAnalysisOptions.defaults());
        AtomicInteger toolCallCount = new AtomicInteger();
        ToolCallback callback = new PrivacyToolCallbackFactory(privacy, ToolDisclosurePolicy.denyAll())
                .wrap(tool(false, "safe tool", toolCallCount));
        InspectionOutputAdvisor output = new InspectionOutputAdvisor(attackBlockingService(),
                new InspectionLimits(10, 8, WAIT), InspectionObserver.noop(), 100, WAIT);
        ChatClient client = PrivacyChatClientConfigurer.builder(privacy)
                .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                .configure(ChatClient.builder(toolModel(new AtomicInteger(), "safe ", "ok", 2)))
                .defaultAdvisors(output).defaultTools(callback).build();
        StepVerifier.create(client.prompt().user("hello").stream().content())
                .expectErrorMatches(error -> error instanceof InspectionException failure
                        && failure.failureCode() == InspectionFailureCode.LIMIT_EXCEEDED)
                .verify(WAIT);
        assertThat(toolCallCount).hasValue(2);
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @Test
    void outputCharacterLimitUsesPrivacyTransformedText() {
        String raw = "sensitive-value ".repeat(20);
        PiiAnalyzer analyzer = (text, options, limits) -> text.contains("sensitive-value")
                ? List.of(new PiiSpan("PERSON", 0, text.length(), 0.95)) : List.of();
        PrivacyService privacy = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        InspectionOutputAdvisor output = new InspectionOutputAdvisor(attackBlockingService(),
                new InspectionLimits(10, 64, WAIT), InspectionObserver.noop(), 100, WAIT);
        ChatClient client = PrivacyChatClientConfigurer.builder(privacy)
                .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                .configure(ChatClient.builder(model(response(raw), Flux.just(response(raw)))))
                .defaultAdvisors(output).build();
        StepVerifier.create(client.prompt().user("hello").stream().content())
                .assertNext(text -> assertThat(text).isNotEmpty().doesNotContain("sensitive-value")
                        .hasSizeLessThanOrEqualTo(64))
                .verifyComplete();
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"false,Tool call limit exceeded", "true,Tool call limit exceeded", "false,attack", "true,attack"})
    void toolLimitResponseAndPrecedingStreamTextArePreserved(boolean privacyEnabled, String text) {
        ChatResponse limitResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason("toolCallLimitExceeded").build())));
        // Spring AI 2.0.0 lacks ToolCallLimitExceededException, which was added in 2.0.1.
        // Supply its response directly so both versions test output inspection of the full stream.
        // Spring AI's exception-to-response conversion is outside this test's scope.
        class LimitResponseToolAdvisor implements ToolAdvisor, StreamAdvisor {
            public String getName() {
                return "LimitResponseToolAdvisor";
            }

            public int getOrder() {
                return 100;
            }

            public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
                return chain.nextStream(request).concatWith(Flux.just(
                        ChatClientResponse.builder().chatResponse(limitResponse).build()));
            }
        }
        ChatResponse intermediate = new ChatResponse(List.of(indexed(7, "checking ")));
        List<String> inspected = new CopyOnWriteArrayList<>();
        PrivacyService privacy = new PrivacyService(List.of((value, options, limits) -> List.of()),
                PiiAnalysisOptions.defaults());
        ChatClient.Builder builder = ChatClient.builder(model(intermediate, Flux.just(intermediate)))
                .defaultAdvisors(recordingAdvisor(inspected), new LimitResponseToolAdvisor());
        if (privacyEnabled) {
            PrivacyChatClientConfigurer.builder(privacy)
                    .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                    .forToolAdvisorOrder(100).configure(builder);
        }
        ChatClient client = builder.build();
        if (text.equals("attack")) {
            assertBlocked(client, true);
        } else {
            StepVerifier.create(client.prompt().user("hello").stream().chatClientResponse())
                    .assertNext(frame -> {
                        assertThat(frame.chatResponse()).isEqualTo(intermediate);
                        assertThat(frame.context()).isEmpty();
                    })
                    .assertNext(frame -> {
                        assertThat(frame.chatResponse()).isEqualTo(limitResponse);
                        assertThat(frame.context()).isEmpty();
                    }).verifyComplete();
        }
        assertThat(inspected).containsExactly("checking ", text);
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"safe earlier", "attack earlier"})
    void anEmptyFinalRoundStillInspectsAndPreservesEarlierText(String text) {
        AtomicInteger rounds = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                throw new AssertionError("stream only");
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    if (rounds.getAndIncrement() == 0) {
                        return Flux.just(response(text), toolCall(""));
                    }
                    return Flux.empty();
                });
            }
        };
        ChatClient client = ChatClient.builder(model).defaultAdvisors(advisor())
                .defaultTools(tool(false, "safe tool", new AtomicInteger())).build();
        if (text.startsWith("attack")) {
            StepVerifier.create(client.prompt().user("hello").stream().content())
                    .expectError(InspectionBlockedException.class).verify(WAIT);
        } else {
            StepVerifier.create(client.prompt().user("hello").stream().content()).expectNext(text).verifyComplete();
        }
    }

    @Test
    void concurrentToolStreamsDoNotShareOutputBuffers() {
        ChatModel model = new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                throw new AssertionError("stream only");
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                boolean followup = prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
                String text = prompt.getUserMessage().getText();
                return followup ? Flux.just(response(text)).delayElements(Duration.ofMillis(10))
                        : Flux.just(response("checking "), toolCall(""));
            }
        };
        ChatClient client = ChatClient.builder(model).defaultAdvisors(advisor())
                .defaultTools(tool(false, "safe tool", new AtomicInteger())).build();
        Mono<List<String>> first = client.prompt().user("first final").stream().content().collectList();
        Mono<List<String>> second = client.prompt().user("second final").stream().content().collectList();
        StepVerifier.create(Mono.zip(first, second))
                .assertNext(pair -> {
                    assertThat(pair.getT1()).containsExactly("checking ", "first final");
                    assertThat(pair.getT2()).containsExactly("checking ", "second final");
                }).verifyComplete();
    }

    @Test
    void outputUsesExistingFailOpenPolicyAndSanitizesHardFailures() {
        ContentInspector outputInspector = new ContentInspector() {
            public String inspectorId() {
                return "unavailable";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                return InspectionResult.failed(InspectionFailureCode.TRANSPORT_ERROR, Set.of(), List.of());
            }
        };
        InspectionService service = new InspectionService(List.of(outputInspector), InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
        ChatResponse safe = response("safe");
        assertThat(client(model(safe, Flux.just(safe)), new InspectionOutputAdvisor(service))
                .prompt().user("hello").call().chatResponse()).isSameAs(safe);
        AtomicInteger failures = new AtomicInteger();
        ContentInspector broken = new ContentInspector() {
            public String inspectorId() {
                return "broken";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                throw new IllegalArgumentException("private-output");
            }
        };
        InspectionObserver observer = new InspectionObserver() {
            public void onInspection(InspectionReport report) {}
            public void onFailure(InspectionException failure) {
                failures.incrementAndGet();
                throw new IllegalArgumentException("observer");
            }
        };
        InspectionOutputAdvisor output = new InspectionOutputAdvisor(new InspectionService(List.of(broken)), InspectionLimits.defaults(), observer, 10, WAIT);
        assertThatThrownBy(() -> client(model(safe, Flux.just(safe)), output).prompt().user("hello").call().content())
                .isInstanceOf(InspectionException.class).hasNoCause().hasMessageNotContaining("private-output");
        assertThat(failures).hasValue(1);
    }
}
