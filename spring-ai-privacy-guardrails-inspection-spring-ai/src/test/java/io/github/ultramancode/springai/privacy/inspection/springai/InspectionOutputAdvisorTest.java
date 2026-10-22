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
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static io.github.ultramancode.springai.privacy.inspection.springai.InspectionTestSupport.attackBlockingService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

class InspectionOutputAdvisorTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private ChatModel model(ChatResponse callResponse, Flux<ChatResponse> streamResponses) {
        return new ChatModel() {
            public ChatResponse call(Prompt prompt) {
                return callResponse;
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return streamResponses;
            }
        };
    }

    private InspectionOutputAdvisor inspectionOutputAdvisor() {
        // Local rule inspection accepts output without a Privacy processing marker.
        return new InspectionOutputAdvisor(attackBlockingService());
    }

    private ChatClient createInspectionClient(ChatModel model, InspectionOutputAdvisor inspectionOutputAdvisor) {
        // Configure input inspection and output inspection for this client.
        return new InspectionChatClientConfigurer(attackBlockingService()).withOutputInspection(inspectionOutputAdvisor)
                .configure(ChatClient.builder(model)).build();
    }

    private Generation indexedGeneration(int choiceIndex, String text) {
        return new Generation(new AssistantMessage(text), ChatGenerationMetadata.builder().metadata("index", choiceIndex).build());
    }

    private InspectionOutputAdvisor recordingOutputAdvisor(List<String> inspectedOutputTexts) {
        ContentInspector recordingInspector = new ContentInspector() {
            public String inspectorId() {
                return "record-output";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                request.segments().forEach(segment -> inspectedOutputTexts.add(segment.text()));
                return InspectionResult.completed(request.segments().stream().map(ContentSegment::id)
                        .collect(Collectors.toSet()), List.of());
            }
        };
        InspectionRule rule = InspectionRule.literal("attack", InspectionFinding.Category.PROMPT_ATTACK, "attack");
        ContentInspector ruleInspector = new RuleBasedContentInspector("rules", List.of(rule));
        InspectionService inspectionService = new InspectionService(List.of(recordingInspector, ruleInspector));
        return new InspectionOutputAdvisor(inspectionService);
    }

    // Return predefined responses based on the call count.
    // This test model does not interpret the prompt or tool results.
    private ChatModel toolCallingModel(AtomicInteger modelCallCount, String intermediateText, String finalText, int toolCallRounds) {
        return new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                if (modelCallCount.getAndIncrement() < toolCallRounds) {
                    return toolCallResponse(intermediateText);
                }
                return response(finalText);
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    if (modelCallCount.getAndIncrement() < toolCallRounds) {
                        return Flux.just(response(intermediateText), toolCallResponse(""));
                    }
                    return Flux.just(response(finalText.substring(0, 2)), response(finalText.substring(2)));
                });
            }
        };
    }

    private ChatResponse toolCallResponse(String text) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text)
                .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "lookup",
                        """
                                {"q":"attack argument"}
                                """.strip()))).build())));
    }

    private ToolCallback toolCallback(boolean returnDirect, String toolResultText, AtomicInteger toolCallCount) {
        return toolCallback("lookup", returnDirect, toolResultText, toolCallCount);
    }

    private ToolCallback toolCallback(String toolName, boolean returnDirect, String toolResultText,
            AtomicInteger toolCallCount) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(toolName).description("fixture").inputSchema("""
                        {}
                        """.strip()).build();
            }

            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().returnDirect(returnDirect).build();
            }

            public String call(String arguments) {
                toolCallCount.incrementAndGet();
                return toolResultText;
            }
        };
    }

    private void assertOutputBlocked(ChatClient client, boolean streaming) {
        if (streaming) {
            StepVerifier.create(client.prompt().user("hello").stream().content())
                    .expectError(InspectionBlockedException.class).verify(TEST_TIMEOUT);
            return;
        }
        assertThatThrownBy(() -> invoke(client, streaming)).isInstanceOf(InspectionBlockedException.class);
    }

    private String invoke(ChatClient client, boolean streaming) {
        if (streaming) {
            return client.prompt().user("hello").stream().content().collectList()
                    .map(parts -> String.join("", parts)).block(TEST_TIMEOUT);
        }
        return client.prompt().user("hello").call().content();
    }

    @Test
    void outputOnlyRegistrationNeedsNoInputInspectionOrPrivacyConfigurer() {
        ChatClient client = ChatClient.builder(model(response("safe"), Flux.just(response("safe"))))
                .defaultAdvisors(inspectionOutputAdvisor()).build();
        assertThat(client.prompt().user("attack").call().content()).isEqualTo("safe");
        StepVerifier.create(client.prompt().user("attack").stream().content())
                .expectNext("safe")
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"safe", "attack"})
    void inspectsResponsesWithoutBuiltInModelAdvisors(String text) {
        ChatClientResponse chatClientResponse = ChatClientResponse.builder().chatResponse(response(text)).build();
        class FixedResponseAdvisor implements CallAdvisor, StreamAdvisor {
            public String getName() {
                return "application-response";
            }

            public int getOrder() {
                return 100;
            }

            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                return chatClientResponse;
            }

            public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
                return Flux.just(chatClientResponse);
            }
        }
        DefaultAroundAdvisorChain chain = DefaultAroundAdvisorChain.builder(ObservationRegistry.NOOP)
                .pushAll(List.of(inspectionOutputAdvisor(), new FixedResponseAdvisor())).build();
        ChatClientRequest request = ChatClientRequest.builder().prompt(new Prompt("hello")).build();
        if (text.equals("attack")) {
            assertThatThrownBy(() -> chain.nextCall(request)).isInstanceOf(InspectionBlockedException.class);
            StepVerifier.create(chain.nextStream(request))
                    .expectError(InspectionBlockedException.class).verify(TEST_TIMEOUT);
        } else {
            assertThat(chain.nextCall(request)).isSameAs(chatClientResponse);
            StepVerifier.create(chain.nextStream(request))
                    .expectNext(chatClientResponse)
                    .expectComplete()
                    .verify(TEST_TIMEOUT);
        }
    }

    @Test
    void blocksAttackOutputOnlyWhenOutputInspectionIsEnabled() {
        ChatModel model = model(response("attack"), Flux.just(response("at"), response("tack")));
        ChatClient inputOnly = new InspectionChatClientConfigurer(attackBlockingService())
                .configure(ChatClient.builder(model)).build();
        assertThat(inputOnly.prompt().user("safe").call().content()).isEqualTo("attack");
        assertThatThrownBy(() -> createInspectionClient(model, inspectionOutputAdvisor()).prompt().user("safe").call().content())
                .isInstanceOf(InspectionBlockedException.class);
        StepVerifier.create(createInspectionClient(model, inspectionOutputAdvisor()).prompt().user("safe").stream().content())
                .expectError(InspectionBlockedException.class).verify(TEST_TIMEOUT);
    }

    @Test
    void preservesOriginalResponsesAndStreamFramesWhenAllowed() {
        ChatResponse firstResponse = response("safe ");
        ChatResponse secondResponse = response("response");
        ChatModel model = model(firstResponse, Flux.just(firstResponse, secondResponse));
        ChatClient client = createInspectionClient(model, inspectionOutputAdvisor());
        assertThat(client.prompt().user("hello").call().chatResponse()).isSameAs(firstResponse);
        for (int i = 0; i < 2; i++) {
            StepVerifier.create(client.prompt().user("hello").stream().chatResponse())
                    .assertNext(actual -> assertThat(actual).isSameAs(firstResponse))
                    .assertNext(actual -> assertThat(actual).isSameAs(secondResponse))
                    .expectComplete()
                    .verify(TEST_TIMEOUT);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputInspectionChecksOnlyBodyText(boolean streaming) {
        List<Generation> generationsWithExcludedContent = List.of(
                new Generation(DeepSeekAssistantMessage.builder().content("safe").reasoningContent("attack").build()),
                new Generation(AssistantMessage.builder().content("safe").properties(Map.of("thinking", "attack")).build()),
                new Generation(new AssistantMessage("safe"), ChatGenerationMetadata.builder().metadata("reasoningContent", "attack").build()),
                new Generation(AssistantMessage.builder().content("safe").toolCalls(List.of(
                        new AssistantMessage.ToolCall("id", "function", "tool", """
                                {"q":"attack"}
                                """.strip()))).build()));
        for (Generation generation : generationsWithExcludedContent) {
            ChatResponse response = new ChatResponse(List.of(generation));
            ChatClient client = createInspectionClient(model(response, Flux.just(response)), inspectionOutputAdvisor());
            assertThat(invoke(client, streaming)).isEqualTo("safe");
        }
        List<ChatResponse> responsesWithAttackText = List.of(
                new ChatResponse(List.of(new Generation(new AssistantMessage("safe")),
                        new Generation(new AssistantMessage("attack")))),
                new ChatResponse(List.of(new Generation(
                        DeepSeekAssistantMessage.builder().content("attack").reasoningContent("safe").build()))));
        for (ChatResponse response : responsesWithAttackText) {
            assertOutputBlocked(createInspectionClient(model(response, Flux.just(response)), inspectionOutputAdvisor()), streaming);
        }
    }

    @Test
    void streamingCombinesTextByChoiceIndex() {
        ChatResponse firstFrame = new ChatResponse(List.of(indexedGeneration(0, "safe"), indexedGeneration(1, """
                {"text":"at\\u0074
                """.strip())));
        ChatResponse secondFrame = new ChatResponse(List.of(indexedGeneration(1, """
                ack"}
                """.strip()), indexedGeneration(0, " value")));
        List<String> inspectedOutputTexts = new CopyOnWriteArrayList<>();
        ChatClient client = createInspectionClient(model(firstFrame, Flux.just(firstFrame, secondFrame)), recordingOutputAdvisor(inspectedOutputTexts));
        StepVerifier.create(client.prompt().user("hello").stream().chatResponse())
                .assertNext(actual -> assertThat(actual).isSameAs(firstFrame))
                .assertNext(actual -> assertThat(actual).isSameAs(secondFrame)).verifyComplete();
        assertThat(inspectedOutputTexts).containsExactly("safe value", """
                {"text":"at\\u0074ack"}
                """.strip());

        ChatResponse responseWithoutChoiceIndex = response("tail");
        // The next frame has no choice index, so it cannot be matched to the preceding indexed choices.
        ChatModel chatModel = model(firstFrame, Flux.just(firstFrame, responseWithoutChoiceIndex));
        ChatClient chatClient = createInspectionClient(chatModel, inspectionOutputAdvisor());

        Flux<String> outputStream = chatClient.prompt()
                .user("hello")
                .stream()
                .content();

        StepVerifier.create(outputStream)
                .expectErrorMatches(error -> error instanceof InspectionException inspectionException
                        && inspectionException.code() == InspectionFailureCode.UNSUPPORTED_CONTENT)
                .verify(TEST_TIMEOUT);
    }

    @Test
    void outputInspectionIgnoresReasoningAndMetadata() {
        ChatResponse firstReasoningFrame = new ChatResponse(List.of(new Generation(DeepSeekAssistantMessage.builder().content("").reasoningContent("at").build())));
        ChatResponse secondReasoningFrame = new ChatResponse(List.of(new Generation(DeepSeekAssistantMessage.builder().content("").reasoningContent("tack").build())));
        StepVerifier.create(createInspectionClient(model(firstReasoningFrame, Flux.just(firstReasoningFrame, secondReasoningFrame)), inspectionOutputAdvisor()).prompt().user("hello").stream().chatResponse())
                .expectNextCount(2).verifyComplete();
        ChatResponse safeResponse = new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("""
                {"answer":"safe"}
                """.strip())
                .properties(Map.of("signature", "attack", "thinking", Map.of("opaque", "attack"))).build())));
        ChatClient safeOutputClient = createInspectionClient(
                model(safeResponse, Flux.just(safeResponse)), inspectionOutputAdvisor());
        assertThat(safeOutputClient.prompt().user("hello").call().chatResponse()).isSameAs(safeResponse);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputRulesCanMatchJsonKeysAndStructure(boolean streaming) {
        InspectionRule rule = InspectionRule.regex("action", InspectionFinding.Category.PROMPT_ATTACK,
                "^\\{\\s*\"action\"\\s*:\\s*\"attack\"\\s*\\}$");
        InspectionService inspectionService = new InspectionService(List.of(new RuleBasedContentInspector("rules", List.of(rule))));
        InspectionOutputAdvisor inspectionOutputAdvisor = new InspectionOutputAdvisor(inspectionService);
        String allowedJson = """
                {"note":"attack"}
                """.strip();
        ChatResponse allowedResponse = response(allowedJson);
        assertThat(invoke(createInspectionClient(model(allowedResponse, Flux.just(allowedResponse)), inspectionOutputAdvisor), streaming)).isEqualTo(allowedJson);

        ChatResponse blockedResponse = response("""
                {
                  "action": "attack"
                }
                """.strip());
        assertOutputBlocked(createInspectionClient(model(blockedResponse, Flux.just(blockedResponse)), inspectionOutputAdvisor), streaming);
    }

    @Test
    void streamBufferingStopsWhenLimitsAreExceeded() {
        String firstOutputText = "safe";
        List<InspectionLimits> restrictiveLimits = List.of(
                new InspectionLimits(2, firstOutputText.length() - 1, TEST_TIMEOUT),
                new InspectionLimits(1, 100, TEST_TIMEOUT)); // Two output bodies exceed the one-segment limit.

        for (InspectionLimits limits : restrictiveLimits) {
            AtomicInteger failureNotificationCount = new AtomicInteger();
            InspectionObserver observer = new InspectionObserver() {
                public void onInspection(InspectionReport report, InspectionExecution execution) {
                    fail("No inspection before assembly succeeds");
                }

                public void onFailure(InspectionException failure, InspectionExecution execution) {
                    failureNotificationCount.incrementAndGet();
                }
            };
            InspectionOutputAdvisor inspectionOutputAdvisor = new InspectionOutputAdvisor(
                    attackBlockingService(), limits, observer, 10, TEST_TIMEOUT);
            ChatResponse responseExceedingLimits = new ChatResponse(List.of(indexedGeneration(0, firstOutputText), indexedGeneration(1, "x")));
            StepVerifier.create(createInspectionClient(
                            model(responseExceedingLimits, Flux.just(responseExceedingLimits)), inspectionOutputAdvisor)
                    .prompt().user("hello").stream().content())
                    .expectErrorMatches(error -> error instanceof InspectionException inspectionException
                        && inspectionException.code() == InspectionFailureCode.LIMIT_EXCEEDED)
                    .verify(TEST_TIMEOUT);
            assertThat(failureNotificationCount).hasValue(1);
        }
        // Empty responses still count as frames. The third exceeds the two-frame limit.
        InspectionOutputAdvisor inspectionOutputAdvisor = new InspectionOutputAdvisor(
                attackBlockingService(), InspectionLimits.defaults(),
                InspectionObserver.noop(), 2, TEST_TIMEOUT);
        StepVerifier.create(createInspectionClient(model(response(""), Flux.just(response(""), response(""), response(""))), inspectionOutputAdvisor)
                        .prompt().user("hello").stream().chatResponse())
                .expectErrorMatches(error -> error instanceof InspectionException inspectionException
                        && inspectionException.code() == InspectionFailureCode.LIMIT_EXCEEDED)
                .verify(TEST_TIMEOUT);
    }

    @Test
    void streamTimeoutAndCancellationDoNotReleaseBufferedOutput() {
        AtomicInteger sourceCancellationCount = new AtomicInteger();
        InspectionOutputAdvisor timeoutOutputAdvisor = new InspectionOutputAdvisor(
                attackBlockingService(), InspectionLimits.defaults(), InspectionObserver.noop(),
                100, Duration.ofMillis(150));
        // Frames keep arriving, so collection must stop at the total timeout.
        Flux<ChatResponse> ongoingModelResponses = Flux.interval(Duration.ofMillis(20))
                .map(ignoredTick -> response("safe"))
                .doOnCancel(sourceCancellationCount::incrementAndGet);
        ChatModel timeoutModel = model(response("safe"), ongoingModelResponses);
        ChatClient timeoutClient = createInspectionClient(timeoutModel, timeoutOutputAdvisor);
        Flux<String> outputUntilTimeout = timeoutClient.prompt().user("hello").stream().content();

        StepVerifier.create(outputUntilTimeout)
                .expectErrorMatches(error -> error instanceof InspectionException inspectionException
                        && inspectionException.code() == InspectionFailureCode.TIMEOUT)
                .verify(TEST_TIMEOUT);
        assertThat(sourceCancellationCount).hasValue(1);

        Flux<ChatResponse> modelResponsesUntilCancelled = Flux.just(response("safe"))
                .concatWith(Flux.never())
                .doOnCancel(sourceCancellationCount::incrementAndGet);
        ChatModel cancellationModel = model(response("safe"), modelResponsesUntilCancelled);
        ChatClient cancellationClient = createInspectionClient(cancellationModel, inspectionOutputAdvisor());
        Flux<String> outputUntilCancelled = cancellationClient.prompt().user("hello").stream().content();

        // The verifier checks for no signals, then cancels the subscription.
        StepVerifier.create(outputUntilCancelled)
                .expectTimeout(Duration.ofMillis(100))
                .verify(TEST_TIMEOUT);
        assertThat(sourceCancellationCount).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void countsEachOutputBodyAsOneSegment(boolean streaming) {
        String jsonBody = """
                {
                  "first": "safe",
                  "second": "safe",
                  "enabled": true,
                  "missing": null
                }
                """;
        InspectionLimits inspectionLimits = new InspectionLimits(1, jsonBody.length(), TEST_TIMEOUT);
        InspectionOutputAdvisor inspectionOutputAdvisor = new InspectionOutputAdvisor(
                attackBlockingService(), inspectionLimits, InspectionObserver.noop(), 10, TEST_TIMEOUT);
        ChatResponse singleBodyResponse = response(jsonBody);
        ChatModel singleBodyModel = model(singleBodyResponse, Flux.just(singleBodyResponse));
        ChatClient singleBodyClient = ChatClient.builder(singleBodyModel)
                .defaultAdvisors(inspectionOutputAdvisor)
                .build();
        assertThat(invoke(singleBodyClient, streaming)).isEqualTo(jsonBody);

        ChatResponse multipleBodyResponse = new ChatResponse(List.of(indexedGeneration(0, "safe"), indexedGeneration(1, "safe")));
        ChatModel multipleBodyModel = model(multipleBodyResponse, Flux.just(multipleBodyResponse));
        ChatClient multipleBodyClient = ChatClient.builder(multipleBodyModel)
                .defaultAdvisors(inspectionOutputAdvisor)
                .build();
        assertThatThrownBy(() -> invoke(multipleBodyClient, streaming))
                .isInstanceOfSatisfying(InspectionException.class,
                        inspectionException -> assertThat(inspectionException.code())
                                .isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED));
    }

    @Test
    void preservesModelStreamErrorWithoutReleasingBufferedOutput() {
        IllegalStateException modelStreamException = new IllegalStateException("fixture model failure");
        StepVerifier.create(createInspectionClient(model(response("safe"), Flux.just(response("safe")).concatWith(Flux.error(modelStreamException))), inspectionOutputAdvisor())
                        .prompt().user("hello").stream().content())
                .expectErrorMatches(error -> error == modelStreamException).verify(TEST_TIMEOUT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outputDoesNotInheritInputPrivacyStatus(boolean streaming) {
        List<ContentSegment> inspectedOutputSegments = new CopyOnWriteArrayList<>();
        ContentInspector inspector = new ContentInspector() {
            public String inspectorId() {
                return "output-status";
            }

            // Skip the service precheck so inspect() can capture the output status before rejecting it.
            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                inspectedOutputSegments.addAll(request.segments());
                request.requirePrivacyProcessed();
                return InspectionResult.completed(request.segments().stream().map(ContentSegment::id)
                        .collect(Collectors.toSet()), List.of());
            }
        };
        PrivacyService privacyService = new PrivacyService(List.of((text, options, limits) -> List.of()), PiiAnalysisOptions.defaults());
        InspectionChatClientConfigurer inspectionChatClientConfigurer = new InspectionChatClientConfigurer(attackBlockingService())
                .withOutputInspection(new InspectionOutputAdvisor(new InspectionService(List.of(inspector))));
        // Enable input Privacy only. Its processing marker must not carry over to the model's output.
        ChatClient client = ModelRequestBoundaryConfigurer.compose(new PrivacyChatClientConfigurer(privacyService), inspectionChatClientConfigurer)
                .configure(ChatClient.builder(model(response("fresh"), Flux.just(response("fresh"))))).build();
        assertThatThrownBy(() -> invoke(client, streaming)).hasMessageContaining("PRIVACY_PROCESSING_REQUIRED");
        assertThat(inspectedOutputSegments).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.UNKNOWN));
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest(name = "streaming={0}, finalText={1}")
    @CsvSource(textBlock = """
            # streaming, finalText
            false, safe final
            false, attack
            true,  safe final
            true,  attack
            """)
    void inspectsAllDeliveredTextAfterToolExecution(boolean streaming, String finalText) {
        AtomicInteger toolCallCount = new AtomicInteger();
        AtomicInteger modelCallCount = new AtomicInteger();
        List<String> inspectedOutputTexts = new CopyOnWriteArrayList<>();
        InspectionOutputAdvisor inspectionOutputAdvisor = recordingOutputAdvisor(inspectedOutputTexts);
        ChatClient client = ChatClient.builder(toolCallingModel(modelCallCount, "checking ", finalText, 2))
                .defaultAdvisors(inspectionOutputAdvisor).defaultTools(toolCallback(false, "safe tool", toolCallCount)).build();
        String expectedText = finalText;
        if (streaming) {
            expectedText = "checking checking " + finalText;
        }
        if (finalText.equals("attack")) {
            assertOutputBlocked(client, streaming);
        } else {
            assertThat(invoke(client, streaming)).isEqualTo(expectedText);
        }
        assertThat(toolCallCount).hasValue(2);
        assertThat(inspectedOutputTexts).containsExactly(expectedText);
    }

    @ParameterizedTest(name = "before={0}, after={1}")
    @CsvSource(textBlock = """
            # textBeforeToolCall, textAfterToolCall
            'attack ', safe final
            at,        tack
            """)
    void streamingInspectsModelTextBeforeAndAfterToolCall(String textBeforeToolCall, String textAfterToolCall) {
        List<String> inspectedOutputTexts = new CopyOnWriteArrayList<>();
        AtomicInteger toolCallCount = new AtomicInteger();
        ChatModel model = toolCallingModel(
                new AtomicInteger(), textBeforeToolCall, textAfterToolCall, 1);
        ToolCallback lookupTool = toolCallback(false, "safe tool", toolCallCount);
        InspectionOutputAdvisor inspectionOutputAdvisor = recordingOutputAdvisor(inspectedOutputTexts);

        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(inspectionOutputAdvisor)
                .defaultTools(lookupTool)
                .build();

        StepVerifier.create(client.prompt().user("hello").stream().content())
                .expectError(InspectionBlockedException.class).verify(TEST_TIMEOUT);
        // Inspect model-generated text before and after the tool call.
        // The tool result ("safe tool") is not included.
        assertThat(inspectedOutputTexts).containsExactly(textBeforeToolCall + textAfterToolCall);
        assertThat(toolCallCount).hasValue(1);
    }

    @ParameterizedTest(name = "streaming={0}, toolResultCount={1}, directToolResultText={2}")
    @CsvSource(textBlock = """
            # streaming, toolResultCount, directToolResultText
            false, 1, safe direct
            false, 1, attack
            false, 2, safe direct
            false, 2, attack
            true,  1, safe direct
            true,  1, attack
            true,  2, safe direct
            true,  2, attack
            """)
    void inspectsAllDirectToolResultsAndPreservesStreamingText(
            boolean streaming, int toolResultCount, String directToolResultText) {
        String intermediateModelText = "checking ";
        AtomicInteger toolCallCount = new AtomicInteger();
        List<AssistantMessage.ToolCall> toolCalls;
        List<String> expectedToolResultTexts;
        if (toolResultCount == 1) {
            toolCalls = List.of(new AssistantMessage.ToolCall("last", "function", "lastLookup", """
                    {}
                    """.strip()));
            expectedToolResultTexts = List.of(directToolResultText);
        } else {
            toolCalls = List.of(
                    new AssistantMessage.ToolCall("first", "function", "firstLookup", """
                            {}
                            """.strip()),
                    new AssistantMessage.ToolCall("last", "function", "lastLookup", """
                            {}
                            """.strip()));
            expectedToolResultTexts = List.of("safe first", directToolResultText);
        }

        List<String> expectedInspectedOutputTexts = new ArrayList<>();
        if (streaming) {
            expectedInspectedOutputTexts.add(intermediateModelText);
        }
        expectedInspectedOutputTexts.addAll(expectedToolResultTexts);

        // invoke() reads content(), so the expected text includes only the first tool result.
        String expectedDeliveredOutputText = expectedToolResultTexts.get(0);
        if (streaming) {
            expectedDeliveredOutputText = intermediateModelText + expectedDeliveredOutputText;
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
                return Flux.just(response(intermediateModelText), toolCallResponse);
            }
        };
        List<String> inspectedOutputTexts = new CopyOnWriteArrayList<>();
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(recordingOutputAdvisor(inspectedOutputTexts))
                .defaultTools(toolCallback("firstLookup", true, "safe first", toolCallCount),
                        toolCallback("lastLookup", true, directToolResultText, toolCallCount))
                .build();
        if (directToolResultText.equals("attack")) {
            assertOutputBlocked(client, streaming);
        } else {
            assertThat(invoke(client, streaming)).isEqualTo(expectedDeliveredOutputText);
        }
        assertThat(toolCallCount).hasValue(toolResultCount);
        assertThat(inspectedOutputTexts).containsExactlyElementsOf(expectedInspectedOutputTexts);
    }

    @ParameterizedTest(name = "streaming={0}, inspectionOutputAdvisorFirst={1}, action={2}")
    @CsvSource(textBlock = """
            # streaming, inspectionOutputAdvisorFirst, action
            false, false, REDACT
            false, false, TOKENIZE
            false, true,  REDACT
            false, true,  TOKENIZE
            true,  false, REDACT
            true,  false, TOKENIZE
            true,  true,  REDACT
            true,  true,  TOKENIZE
            """)
    void inspectsTextAfterPrivacyOutputTransformationInEitherRegistrationOrder(
            boolean streaming, boolean inspectionOutputAdvisorFirst, PrivacyOutputAction action) {
        PiiAnalyzer analyzer = (text, options, limits) -> {
            int attackStartIndex = text.indexOf("attack");
            return attackStartIndex < 0 ? List.of() : List.of(new PiiSpan(
                    "PERSON", attackStartIndex, attackStartIndex + "attack".length(), 0.95));
        };

        PrivacyService privacyService = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        PrivacyChatClientConfigurer privacyChatClientConfigurer = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(action, "blocked").build();
        List<String> inspectedOutputTexts = new CopyOnWriteArrayList<>();
        InspectionOutputAdvisor inspectionOutputAdvisor = recordingOutputAdvisor(inspectedOutputTexts);
        ChatClient.Builder builder = ChatClient.builder(model(response("attack"), Flux.just(response("at"), response("tack"))));
        // Advisor order must put Privacy output protection before inspection in either registration order.
        if (inspectionOutputAdvisorFirst) {
            builder.defaultAdvisors(inspectionOutputAdvisor);
        }
        privacyChatClientConfigurer.configure(builder);
        if (!inspectionOutputAdvisorFirst) {
            builder.defaultAdvisors(inspectionOutputAdvisor);
        }
        String deliveredOutputText = invoke(builder.build(), streaming);
        assertThat(deliveredOutputText).doesNotContain("attack");
        assertThat(inspectedOutputTexts).containsExactly(deliveredOutputText);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void privacyOutputProtectionBlocksIntermediateTextBeforeInspection() {
        PiiAnalyzer analyzer = (text, options, limits) -> {
            int attackStartIndex = text.indexOf("attack intermediate");
            return attackStartIndex < 0 ? List.of() : List.of(new PiiSpan(
                    "PERSON", attackStartIndex, attackStartIndex + "attack intermediate".length(), 0.95));
        };

        PrivacyService privacyService = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        PrivacyChatClientConfigurer privacyChatClientConfigurer = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(PrivacyOutputAction.BLOCK, "blocked").build();
        AtomicInteger toolCallCount = new AtomicInteger();
        ToolCallback privacyProtectedToolCallback = new PrivacyToolCallbackFactory(privacyService,
                ToolDisclosurePolicy.denyAll())
                .wrap(toolCallback(false, "safe tool", toolCallCount));
        ChatClient client = privacyChatClientConfigurer.configure(ChatClient.builder(toolCallingModel(new AtomicInteger(), "attack intermediate", "safe final", 2)))
                .defaultAdvisors(inspectionOutputAdvisor()).defaultTools(privacyProtectedToolCallback).build();
        StepVerifier.create(client.prompt().user("hello").stream().content())
                .expectError(PrivacyOutputBlockedException.class).verify(TEST_TIMEOUT);
        assertThat(toolCallCount).hasValue(2);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void inputOnlyInspectionLeavesStreamedOutputUnchanged() {
        InspectionRule rule = InspectionRule.literal(
                "blocked-output", InspectionFinding.Category.PROMPT_ATTACK, "blocked output");
        ContentInspector inspector = new RuleBasedContentInspector("rules", List.of(rule));
        InspectionService inspectionService = new InspectionService(List.of(inspector));
        ChatModel model = toolCallingModel(new AtomicInteger(), "checking ", "blocked output", 1);
        ChatClient client = new InspectionChatClientConfigurer(inspectionService).configure(ChatClient.builder(model))
                .defaultTools(toolCallback(false, "safe tool", new AtomicInteger())).build();

        // Input-only inspection does not block or modify the model's output.
        assertThat(invoke(client, true)).isEqualTo("checking blocked output");

        // The same phrase is blocked when supplied as user input.
        StepVerifier.create(client.prompt().user("blocked output").stream().content())
                .expectError(InspectionBlockedException.class).verify(TEST_TIMEOUT);
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void outputFrameLimitAppliesAcrossToolRounds(int maxFrames) {
        InspectionOutputAdvisor inspectionOutputAdvisor = new InspectionOutputAdvisor(attackBlockingService(), InspectionLimits.defaults(),
                InspectionObserver.noop(), maxFrames, TEST_TIMEOUT);
        // Output inspection receives four frames: two "checking " frames,
        // followed by "safe final" split into "sa" and "fe final" by the helper.
        ChatClient client = ChatClient.builder(toolCallingModel(new AtomicInteger(), "checking ", "safe final", 2))
                .defaultAdvisors(inspectionOutputAdvisor).defaultTools(toolCallback(false, "safe tool", new AtomicInteger())).build();
        Flux<String> content = client.prompt().user("hello").stream().content();
        if (maxFrames == 3) {
            StepVerifier.create(content)
                    .expectErrorMatches(error -> error instanceof InspectionException failure
                            && failure.code() == InspectionFailureCode.LIMIT_EXCEEDED)
                    .verify(TEST_TIMEOUT);
        } else {
            StepVerifier.create(content)
                    .expectNext("checking ", "checking ", "sa", "fe final").verifyComplete();
        }
    }

    @Test
    void outputCharacterLimitAppliesAcrossToolRoundsWithPrivacyProtection() {
        PrivacyService privacyService = new PrivacyService(List.of((text, options, limits) -> List.of()),
                PiiAnalysisOptions.defaults());
        AtomicInteger toolCallCount = new AtomicInteger();
        ToolCallback privacyProtectedToolCallback = new PrivacyToolCallbackFactory(privacyService, ToolDisclosurePolicy.denyAll())
                .wrap(toolCallback(false, "safe tool", toolCallCount));
        InspectionOutputAdvisor inspectionOutputAdvisor = new InspectionOutputAdvisor(attackBlockingService(),
                new InspectionLimits(10, 8, TEST_TIMEOUT), InspectionObserver.noop(), 100, TEST_TIMEOUT);
        ChatClient client = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                .configure(ChatClient.builder(toolCallingModel(new AtomicInteger(), "safe ", "ok", 2)))
                .defaultAdvisors(inspectionOutputAdvisor).defaultTools(privacyProtectedToolCallback).build();
        StepVerifier.create(client.prompt().user("hello").stream().content())
                .expectErrorMatches(error -> error instanceof InspectionException failure
                        && failure.code() == InspectionFailureCode.LIMIT_EXCEEDED)
                .verify(TEST_TIMEOUT);
        assertThat(toolCallCount).hasValue(2);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @Test
    void outputCharacterLimitUsesPrivacyTransformedText() {
        int maxOutputCharacters = 20;
        String rawOutputText = "x".repeat(maxOutputCharacters + 1);
        // Redaction replaces the entire value with "[REDACTED_PERSON]" (17 characters).
        PiiAnalyzer analyzer = (text, options, limits) -> text.contains(rawOutputText)
                ? List.of(new PiiSpan("PERSON", 0, text.length(), 0.95)) : List.of();
        PrivacyService privacyService = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
        InspectionOutputAdvisor inspectionOutputAdvisor = new InspectionOutputAdvisor(attackBlockingService(),
                new InspectionLimits(10, maxOutputCharacters, TEST_TIMEOUT), InspectionObserver.noop(), 100, TEST_TIMEOUT);
        ChatClient client = PrivacyChatClientConfigurer.builder(privacyService)
                .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                .configure(ChatClient.builder(model(response(rawOutputText), Flux.just(response(rawOutputText)))))
                .defaultAdvisors(inspectionOutputAdvisor).build();
        StepVerifier.create(client.prompt().user("hello").stream().content())
                .assertNext(text -> assertThat(text).isNotEmpty().doesNotContain(rawOutputText)
                        .hasSizeLessThanOrEqualTo(maxOutputCharacters))
                .verifyComplete();
        assertThat(privacyService.activeSessionCount()).isZero();
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
        ChatResponse intermediate = new ChatResponse(List.of(indexedGeneration(7, "checking ")));
        List<String> inspectedOutputTexts = new CopyOnWriteArrayList<>();
        PrivacyService privacyService = new PrivacyService(List.of((value, options, limits) -> List.of()),
                PiiAnalysisOptions.defaults());
        ChatClient.Builder builder = ChatClient.builder(model(intermediate, Flux.just(intermediate)))
                .defaultAdvisors(recordingOutputAdvisor(inspectedOutputTexts), new LimitResponseToolAdvisor());
        if (privacyEnabled) {
            PrivacyChatClientConfigurer.builder(privacyService)
                    .outputProtection(PrivacyOutputAction.REDACT, "blocked").build()
                    .forToolAdvisorOrder(100).configure(builder);
        }
        ChatClient client = builder.build();
        if (text.equals("attack")) {
            assertOutputBlocked(client, true);
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
        assertThat(inspectedOutputTexts).containsExactly("checking ", text);
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"safe earlier", "attack earlier"})
    void anEmptyFinalRoundStillInspectsAndPreservesEarlierText(String text) {
        AtomicInteger modelCallCount = new AtomicInteger();
        ChatModel model = new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                throw new AssertionError("stream only");
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.defer(() -> {
                    if (modelCallCount.getAndIncrement() == 0) {
                        return Flux.just(response(text), toolCallResponse(""));
                    }
                    return Flux.empty();
                });
            }
        };
        ChatClient client = ChatClient.builder(model).defaultAdvisors(inspectionOutputAdvisor())
                .defaultTools(toolCallback(false, "safe tool", new AtomicInteger())).build();
        if (text.startsWith("attack")) {
            StepVerifier.create(client.prompt().user("hello").stream().content())
                    .expectError(InspectionBlockedException.class).verify(TEST_TIMEOUT);
        } else {
            StepVerifier.create(client.prompt().user("hello").stream().content()).expectNext(text).verifyComplete();
        }
    }

    @Test
    void concurrentToolStreamsDoNotShareOutputBuffers() {
        AtomicInteger finalResponseReadyCount = new AtomicInteger();
        Sinks.Empty<Void> bothFinalResponsesReady = Sinks.empty();
        ChatModel model = new ChatModel() {
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }

            public ChatResponse call(Prompt prompt) {
                throw new AssertionError("stream only");
            }

            public Flux<ChatResponse> stream(Prompt prompt) {
                boolean hasToolResponse = prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
                if (!hasToolResponse) {
                    return Flux.just(response("checking "), toolCallResponse(""));
                }
                String userText = prompt.getUserMessage().getText();
                // Release final responses only after both requests have completed their tool calls.
                return Flux.defer(() -> {
                    if (finalResponseReadyCount.incrementAndGet() == 2) {
                        assertThat(bothFinalResponsesReady.tryEmitEmpty()).isEqualTo(Sinks.EmitResult.OK);
                    }
                    return bothFinalResponsesReady.asMono().thenMany(Flux.just(response(userText)));
                });
            }
        };
        ChatClient client = ChatClient.builder(model).defaultAdvisors(inspectionOutputAdvisor())
                .defaultTools(toolCallback(false, "safe tool", new AtomicInteger())).build();
        Mono<List<String>> firstOutputTexts = client.prompt().user("first final").stream().content().collectList();
        Mono<List<String>> secondOutputTexts = client.prompt().user("second final").stream().content().collectList();
        StepVerifier.create(Mono.zip(firstOutputTexts, secondOutputTexts))
                .assertNext(pair -> {
                    assertThat(pair.getT1()).containsExactly("checking ", "first final");
                    assertThat(pair.getT2()).containsExactly("checking ", "second final");
                })
                .expectComplete()
                .verify(TEST_TIMEOUT);
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
        InspectionService inspectionService = new InspectionService(
                List.of(outputInspector), InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
        ChatResponse safeResponse = response("safe");
        assertThat(createInspectionClient(model(safeResponse, Flux.just(safeResponse)), new InspectionOutputAdvisor(inspectionService))
                .prompt().user("hello").call().chatResponse()).isSameAs(safeResponse);
        AtomicInteger failureNotificationCount = new AtomicInteger();
        ContentInspector throwingInspector = new ContentInspector() {
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
            public void onInspection(InspectionReport report, InspectionExecution execution) {}
            public void onFailure(InspectionException failure, InspectionExecution execution) {
                failureNotificationCount.incrementAndGet();
                throw new IllegalArgumentException("observer");
            }
        };
        InspectionOutputAdvisor inspectionOutputAdvisor = new InspectionOutputAdvisor(
                new InspectionService(List.of(throwingInspector)), InspectionLimits.defaults(), observer, 10, TEST_TIMEOUT);
        ChatClient failingInspectionClient = createInspectionClient(
                model(safeResponse, Flux.just(safeResponse)), inspectionOutputAdvisor);
        assertThatThrownBy(() -> failingInspectionClient.prompt().user("hello").call().content())
                .isInstanceOf(InspectionException.class).hasNoCause().hasMessageNotContaining("private-output");
        assertThat(failureNotificationCount).hasValue(1);
    }
}
