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
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.DefaultAroundAdvisorChain;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
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
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.core.PriorityOrdered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
        return new InspectionOutputAdvisor(InspectionRuntimeScopeTest.service());
    }

    private ChatClient client(ChatModel model, InspectionOutputAdvisor output) {
        return new InspectionChatClientConfigurer(InspectionRuntimeScopeTest.service()).withOutputInspection(output)
                .configure(ChatClient.builder(model)).build();
    }

    private Generation indexed(int index, String text) {
        return new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder().metadata("index", index).build());
    }

    private InspectionOutputAdvisor recordingAdvisor(List<String> captured) {
        var recorder = new ContentInspector() {
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
        return new InspectionOutputAdvisor(new InspectionService(List.of(recorder,
                new RuleBasedContentInspector("rules", List.of(
                        InspectionRule.literal("attack",
                                InspectionFinding.Category.PROMPT_ATTACK, "attack"))))));
    }

    private ChatModel toolModel(AtomicInteger rounds, String finalText, int toolRounds) {
        return new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                return rounds.getAndIncrement() < toolRounds ? toolCall("attack intermediate") : response(finalText);
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> rounds.getAndIncrement() < toolRounds
                        ? Flux.just(response("attack intermediate"), toolCall(""))
                        : Flux.just(response(finalText.substring(0, 2)), response(finalText.substring(2))));
            }
        };
    }

    private ChatResponse toolCall(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "lookup",
                        "{\"q\":\"attack argument\"}"))).build())));
    }

    private ToolCallback tool(boolean direct, String result, AtomicInteger calls) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("lookup").description("fixture").inputSchema("{}").build();
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
        var client = ChatClient.builder(model(response("safe"), Flux.just(response("safe"))))
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
        var inputOnly = new InspectionChatClientConfigurer(InspectionRuntimeScopeTest.service())
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
        var client = client(model, advisor());
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
        List<Generation> unsafe = List.of(
                new Generation(DeepSeekAssistantMessage.builder().content("safe").reasoningContent("attack").build()),
                new Generation(AssistantMessage.builder().content("safe").properties(Map.of("thinking", "attack")).build()),
                new Generation(new AssistantMessage("safe"), ChatGenerationMetadata.builder().metadata("reasoningContent", "attack").build()),
                new Generation(AssistantMessage.builder().content("").toolCalls(List.of(
                        new AssistantMessage.ToolCall("id", "function", "tool", "{\"q\":\"attack\"}"))).build()));
        for (Generation generation : unsafe) {
            ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("safe")), generation));
            var client = client(model(response, Flux.just(response)), advisor());
            assertThat(invoke(client, streaming)).contains("safe");
        }
        ChatResponse unsafeText = new ChatResponse(List.of(new Generation(new AssistantMessage("safe")),
                new Generation(new AssistantMessage("attack"))));
        assertBlocked(client(model(unsafeText, Flux.just(unsafeText)), advisor()), streaming);
    }

    @Test
    void streamingCorrelatesReorderedExplicitChoicesAndDecodesSplitJson() {
        var first = new ChatResponse(List.of(indexed(0, "safe"), indexed(1, "{\"attack\":\"at\\u0074")));
        var second = new ChatResponse(List.of(indexed(1, "ack\"}"), indexed(0, " value")));
        StepVerifier.create(client(model(first, Flux.just(first, second)), advisor()).prompt().user("hello").stream().content())
                .expectError(InspectionBlockedException.class).verify(WAIT);
        var ambiguous = new ChatResponse(List.of(new Generation(new AssistantMessage("tail"))));
        StepVerifier.create(client(model(first, Flux.just(first, ambiguous)), advisor()).prompt().user("hello").stream().content())
                .expectErrorMatches(e -> e instanceof InspectionException f && f.failureCode() == InspectionFailureCode.UNSUPPORTED_CONTENT)
                .verify(WAIT);
    }

    @Test
    void splitReasoningMetadataAndJsonKeysAreExcluded() {
        var first = new ChatResponse(List.of(new Generation(DeepSeekAssistantMessage.builder().content("").reasoningContent("at").build())));
        var second = new ChatResponse(List.of(new Generation(DeepSeekAssistantMessage.builder().content("").reasoningContent("tack").build())));
        StepVerifier.create(client(model(first, Flux.just(first, second)), advisor()).prompt().user("hello").stream().chatResponse())
                .expectNextCount(2).verifyComplete();
        var safe = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("{\"attack\":\"safe\"}")
                .properties(Map.of("signature", "attack", "thinking", Map.of("opaque", "attack"))).build())));
        assertThat(client(model(safe, Flux.just(safe)), advisor()).prompt().user("hello").call().chatResponse()).isSameAs(safe);
    }

    @Test
    void outputDoesNotRejectUninspectedToolArgumentFragments() {
        var partial = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
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
            var observer = new InspectionObserver() {
                public void onInspection(InspectionReport report) {
                    fail("No inspection before assembly succeeds");
                }

                public void onFailure(InspectionException failure) {
                    failures.incrementAndGet();
                }
            };
            var output = new InspectionOutputAdvisor(InspectionRuntimeScopeTest.service(), limits, observer, 10, WAIT);
            var excess = new ChatResponse(List.of(indexed(0, "safe"), indexed(1, "x")));
            StepVerifier.create(client(model(excess, Flux.just(excess)), output).prompt().user("hello").stream().content())
                    .expectErrorMatches(e -> e instanceof InspectionException f && f.failureCode() == InspectionFailureCode.LIMIT_EXCEEDED)
                    .verify(WAIT);
            assertThat(failures).hasValue(1);
        }
        var output = new InspectionOutputAdvisor(InspectionRuntimeScopeTest.service(), InspectionLimits.defaults(),
                InspectionObserver.noop(), 2, WAIT);
        StepVerifier.create(client(model(response(""), Flux.just(response(""), response(""), response(""))), output)
                        .prompt().user("hello").stream().chatResponse())
                .expectErrorMatches(e -> e instanceof InspectionException f && f.failureCode() == InspectionFailureCode.LIMIT_EXCEEDED)
                .verify(WAIT);
    }

    @Test
    void totalStreamDeadlineAndCancellationNeverReleaseBufferedContent() {
        AtomicInteger cancelled = new AtomicInteger();
        var output = new InspectionOutputAdvisor(InspectionRuntimeScopeTest.service(), InspectionLimits.defaults(),
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
    void outputJsonValuesShareTheSegmentBudget(boolean streaming) {
        InspectionLimits limits = new InspectionLimits(1, 100, WAIT);
        InspectionOutputAdvisor output = new InspectionOutputAdvisor(InspectionRuntimeScopeTest.service(), limits,
                InspectionObserver.noop(), 10, WAIT);
        ChatResponse response = response("{\"first\":\"safe\",\"second\":\"safe\"}");
        ChatClient client = ChatClient.builder(model(response, Flux.just(response))).defaultAdvisors(output).build();
        assertThatThrownBy(() -> invoke(client, streaming)).isInstanceOf(InspectionException.class)
                .satisfies(error -> assertThat(((InspectionException) error).failureCode())
                        .isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED));
    }

    @Test
    void upstreamErrorIsPreservedWithoutReleasingEarlierFrames() {
        var error = new IllegalStateException("fixture model failure");
        StepVerifier.create(client(model(response("safe"), Flux.just(response("safe")).concatWith(Flux.error(error))), advisor())
                        .prompt().user("hello").stream().content())
                .expectErrorMatches(actual -> actual == error).verify(WAIT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputStatusNeverInheritsInputPrivacyAndSessionsCloseOnBlock(boolean streaming) {
        List<ContentSegment> captured = new CopyOnWriteArrayList<>();
        var inspector = new ContentInspector() {
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
        var privacy = new PrivacyService(List.of((text, options, limits) -> List.of()), PiiAnalysisOptions.defaults());
        var inspection = new InspectionChatClientConfigurer(InspectionRuntimeScopeTest.service())
                .withOutputInspection(new InspectionOutputAdvisor(new InspectionService(List.of(inspector))));
        var client = ModelRequestBoundaryConfigurer.compose(new PrivacyChatClientConfigurer(privacy), inspection)
                .configure(ChatClient.builder(model(response("fresh"), Flux.just(response("fresh"))))).build();
        assertThatThrownBy(() -> invoke(client, streaming)).hasMessageContaining("PRIVACY_PROCESSING_REQUIRED");
        assertThat(captured).allSatisfy(s -> assertThat(s.privacyProcessingStatus()).isEqualTo(ContentSegment.PrivacyProcessingStatus.UNKNOWN));
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inspectsOnlyFinalModelTextAfterToolExecution(boolean streaming) {
        for (String finalText : List.of("safe final", "attack")) {
            AtomicInteger tools = new AtomicInteger();
            AtomicInteger rounds = new AtomicInteger();
            List<String> inspected = new CopyOnWriteArrayList<>();
            var output = recordingAdvisor(inspected);
            var client = ChatClient.builder(toolModel(rounds, finalText, 2))
                    .defaultAdvisors(output).defaultTools(tool(false, "safe tool", tools)).build();
            if (finalText.equals("attack")) {
                assertBlocked(client, streaming);
            } else {
                assertThat(invoke(client, streaming)).isEqualTo(finalText);
            }
            assertThat(tools).hasValue(2);
            assertThat(inspected).containsExactly(finalText);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnDirectIsFinalOutputAndIntermediateCommandsAreNotInspected(boolean streaming) {
        for (String direct : List.of("safe direct", "attack")) {
            AtomicInteger tools = new AtomicInteger();
            List<String> inspected = new CopyOnWriteArrayList<>();
            var client = ChatClient.builder(toolModel(new AtomicInteger(), "unused", 1))
                    .defaultAdvisors(recordingAdvisor(inspected)).defaultTools(tool(true, direct, tools)).build();
            if (direct.equals("attack")) {
                assertBlocked(client, streaming);
            } else {
                assertThat(invoke(client, streaming)).isEqualTo(direct);
            }
            assertThat(tools).hasValue(1);
            assertThat(inspected).containsExactly(direct);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inspectsTextAfterPrivacyOutputTransformationInEitherRegistrationOrder(boolean streaming) {
        for (boolean outputFirst : List.of(false, true)) {
            for (var action : List.of(PrivacyOutputAction.REDACT,
                    PrivacyOutputAction.TOKENIZE)) {
                PiiAnalyzer analyzer = (text, options, limits) -> {
                    int at = text.indexOf("attack");
                    return at < 0 ? List.of() : List.of(new PiiSpan(
                            "PERSON", at, at + 6, 0.95));
                };

                var privacy = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
                var configurer = PrivacyChatClientConfigurer.builder(privacy).outputProtection(action, "blocked").build();
                List<String> inspected = new CopyOnWriteArrayList<>();
                var output = recordingAdvisor(inspected);
                var builder = ChatClient.builder(model(response("attack"), Flux.just(response("at"), response("tack"))));
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
    void filtersIntermediateStreamTextBeforePrivacyOutputProtection() {
        PiiAnalyzer analyzer = (text, options, limits) -> {
            int at = text.indexOf("attack intermediate");
            return at < 0 ? List.of() : List.of(new PiiSpan(
                    "PERSON", at, at + "attack intermediate".length(), 0.95));
        };

        var privacy = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        var configurer = PrivacyChatClientConfigurer.builder(privacy)
                .outputProtection(PrivacyOutputAction.BLOCK, "blocked").build();
        AtomicInteger tools = new AtomicInteger();
        var callback = new PrivacyToolCallbackFactory(privacy,
                ToolDisclosurePolicy.denyAll())
                .wrap(tool(false, "safe tool", tools));
        var client = configurer.configure(ChatClient.builder(toolModel(new AtomicInteger(), "safe final", 2)))
                .defaultAdvisors(advisor()).defaultTools(callback).build();
        assertThat(invoke(client, true)).isEqualTo("safe final");
        assertThat(tools).hasValue(2);
        assertThat(privacy.activeSessionCount()).isZero();
    }

    @Test
    void outputDisabledKeepsIntermediateStreamingText() {
        var client = ChatClient.builder(toolModel(new AtomicInteger(), "safe final", 1))
                .defaultTools(tool(false, "safe tool", new AtomicInteger())).build();
        assertThat(invoke(client, true)).isEqualTo("attack intermediatesafe final");
    }

    @Test
    void toolRoundsShareTheFrameBudgetAndDoNotEmitPartialAnswersOnFailure() {
        var output = new InspectionOutputAdvisor(InspectionRuntimeScopeTest.service(), InspectionLimits.defaults(),
                InspectionObserver.noop(), 3, WAIT);
        var client = ChatClient.builder(toolModel(new AtomicInteger(), "safe final", 2))
                .defaultAdvisors(output).defaultTools(tool(false, "safe tool", new AtomicInteger())).build();
        StepVerifier.create(client.prompt().user("hello").stream().content())
                .expectErrorMatches(e -> e instanceof InspectionException f && f.failureCode() == InspectionFailureCode.LIMIT_EXCEEDED)
                .verify(WAIT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void allDirectResultsAreFinalOutput(boolean streaming) {
        for (String text : List.of("safe result", "attack")) {
            ToolCallingManager manager = Mockito.mock(ToolCallingManager.class);
            ToolExecutionResult result = ToolExecutionResult.builder().returnDirect(true)
                    .conversationHistory(List.of(ToolResponseMessage.builder().responses(List.of(
                            new ToolResponseMessage.ToolResponse("first", "lookup", "safe first"),
                            new ToolResponseMessage.ToolResponse("last", "lookup", text))).build()))
                    .build();
            Mockito.when(manager.executeToolCalls(ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(result);
            List<String> inspected = new CopyOnWriteArrayList<>();
            ChatClient client = ChatClient.builder(toolModel(new AtomicInteger(), "unused", 1))
                    .defaultAdvisors(recordingAdvisor(inspected),
                            ToolCallingAdvisor.builder()
                                    .toolCallingManager(manager).advisorOrder(100).build()).build();
            if (text.equals("attack")) {
                assertBlocked(client, streaming);
            } else {
                String delivered = invoke(client, streaming);
                assertThat(delivered).doesNotContain("attack intermediate");
            }
            assertThat(inspected).containsExactly("safe first", text);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tool call limit exceeded", "attack"})
    void toolLimitResponseReplacesIntermediateStreamText(String text) {
        ChatResponse limitResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(text),
                ChatGenerationMetadata.builder().finishReason("toolCallLimitExceeded").build())));
        // Spring AI 2.0.0 lacks ToolCallLimitExceededException, which was added in 2.0.1.
        // Supply its response directly so both versions test how intermediate text is replaced.
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
        ChatResponse intermediate = response("attack intermediate");
        List<String> inspected = new CopyOnWriteArrayList<>();
        ChatClient client = ChatClient.builder(model(intermediate, Flux.just(intermediate)))
                .defaultAdvisors(recordingAdvisor(inspected), new LimitResponseToolAdvisor()).build();
        if (text.equals("attack")) {
            assertBlocked(client, true);
        } else {
            StepVerifier.create(client.prompt().user("hello").stream().chatResponse())
                    .expectNext(limitResponse).verifyComplete();
        }
        assertThat(inspected).containsExactly(text);
    }

    @Test
    void anEmptyFinalRoundDoesNotReleaseIntermediateText() {
        var rounds = new AtomicInteger();
        var model = new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                throw new AssertionError("stream only");
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> rounds.getAndIncrement() == 0
                        ? Flux.just(response("attack intermediate"), toolCall("")) : Flux.empty());
            }
        };
        var client = ChatClient.builder(model).defaultAdvisors(advisor())
                .defaultTools(tool(false, "safe tool", new AtomicInteger())).build();
        StepVerifier.create(client.prompt().user("hello").stream().content()).verifyComplete();
    }

    @Test
    void cannotSilentlyRunFinalStreamFilteringInsideAPriorityToolLoop() {
        class PriorityTool implements ToolAdvisor, StreamAdvisor, PriorityOrdered {
            public String getName() {
                return "priority-tool";
            }

            public int getOrder() {
                return 0;
            }

            public Flux<ChatClientResponse> adviseStream(
                    ChatClientRequest request, StreamAdvisorChain chain) {
                throw new AssertionError("Invalid output boundary must be rejected before tool execution");
            }
        }
        var client = ChatClient.builder(model(response("safe"), Flux.just(response("safe"))))
                .defaultAdvisors(advisor(), new PriorityTool()).build();
        StepVerifier.create(client.prompt().user("hello").stream().content())
                .expectError(IllegalStateException.class).verify(WAIT);
    }

    @Test
    void concurrentToolStreamsDoNotShareRoundBuffers() {
        var model = new ChatModel() {
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
                        : Flux.just(response("attack intermediate"), toolCall(""));
            }
        };
        var client = ChatClient.builder(model).defaultAdvisors(advisor())
                .defaultTools(tool(false, "safe tool", new AtomicInteger())).build();
        var first = client.prompt().user("first final").stream().content().collectList();
        var second = client.prompt().user("second final").stream().content().collectList();
        StepVerifier.create(Mono.zip(first, second))
                .assertNext(pair -> {
                    assertThat(pair.getT1()).containsExactly("first final");
                    assertThat(pair.getT2()).containsExactly("second final");
                }).verifyComplete();
    }

    @Test
    void outputUsesExistingFailOpenPolicyAndSanitizesHardFailures() {
        var outputInspector = new ContentInspector() {
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
        var service = new InspectionService(List.of(outputInspector), InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
        ChatResponse safe = response("safe");
        assertThat(client(model(safe, Flux.just(safe)), new InspectionOutputAdvisor(service))
                .prompt().user("hello").call().chatResponse()).isSameAs(safe);
        AtomicInteger failures = new AtomicInteger();
        var broken = new ContentInspector() {
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
        var observer = new InspectionObserver() {
            public void onInspection(InspectionReport report) {}
            public void onFailure(InspectionException failure) {
                failures.incrementAndGet();
                throw new IllegalArgumentException("observer");
            }
        };
        var output = new InspectionOutputAdvisor(new InspectionService(List.of(broken)), InspectionLimits.defaults(), observer, 10, WAIT);
        assertThatThrownBy(() -> client(model(safe, Flux.just(safe)), output).prompt().user("hello").call().content())
                .isInstanceOf(InspectionException.class).hasNoCause().hasMessageNotContaining("private-output");
        assertThat(failures).hasValue(1);
    }
}
