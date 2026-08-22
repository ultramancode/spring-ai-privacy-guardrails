package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.net.MalformedURLException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrivacyOutputAdvisorStreamTest {
    @Test
    void adviseStreamBuffersAllChunksBeforeTokenizingCrossChunkPii() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        AtomicReference<PrivacyContextHandle> downstreamHandle = new AtomicReference<>();
        when(chain.nextStream(any())).thenAnswer(invocation -> {
            ChatClientRequest protectedRequest = invocation.getArgument(0);
            downstreamHandle.set(PrivacyRequestContextSupport.findHandle(protectedRequest).orElseThrow());
            return Flux.just(
                    new ChatClientResponse(response("Ali").chatResponse(), protectedRequest.context()),
                    new ChatClientResponse(response("ce is available").chatResponse(), protectedRequest.context())
            );
        });

        try (PrivacySession session = service.openSession()) {
            List<ChatClientResponse> results = protectStreamAtApplicationBoundary(
                    advisor, session.handle(), chain
            ).collectList().block();

            assertThat(results).hasSize(2);
            assertThat(streamText(results, 0))
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern() + " is available")
                    .doesNotContain("Alice");
            assertThat(results.get(0).chatResponse().getResult().getOutput().getText()).isNull();
            assertThat(results)
                    .allSatisfy(result -> assertThat(result.context())
                            .containsEntry(PrivacyRequestContextSupport.CONTEXT_HANDLE, session.handle()));
            assertThat(downstreamHandle.get()).isEqualTo(session.handle());
            assertThat(service.isSessionActive(session.handle())).isTrue();
        }
        assertThat(service.activeSessionCount()).isZero();
    }

    @Test
    void returnDirectStreamRestoresTokensBeforeApplyingTheOutputPolicy() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.REDACT,
                "blocked"
        );
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);

        try (PrivacySession session = service.openSession()) {
            String tokenizedResult = PrivacyJsonPayloadTransformer.tokenize(
                    service,
                    session.handle(),
                    "{\"owner\":\"Alice\"}",
                    PrivacyPhase.TOOL_OUTPUT,
                    true
            );
            int split = tokenizedResult.length() / 2;
            when(chain.nextStream(any())).thenReturn(Flux.just(
                    response(tokenizedResult.substring(0, split)),
                    response(List.of(new Generation(
                            new AssistantMessage(tokenizedResult.substring(split)),
                            ChatGenerationMetadata.builder()
                                    .finishReason(ToolExecutionResult.FINISH_REASON)
                                    .build()
                    )))
            ));

            List<ChatClientResponse> results = protectStreamAtApplicationBoundary(
                    advisor,
                    session.handle(),
                    chain
            ).collectList().block();

            assertThat(streamText(results, 0))
                    .contains("[REDACTED_PERSON]")
                    .doesNotContain("Alice", "[[PII_");
        }
    }

    @Test
    void adviseStreamLeavesNormalFramesIncrementalForTheLifecycleBoundary() {
        AtomicInteger analysisCalls = new AtomicInteger();
        PrivacyService service = privacyService(analysisCalls);
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(response("Ali"), response("ce")));

        try (PrivacySession session = service.openSession()) {
            List<String> text = advisor.adviseStream(request(session.handle()), chain)
                    .map(item -> item.chatResponse().getResult().getOutput().getText())
                    .collectList()
                    .block();

            assertThat(text).containsExactly("Ali", "ce");
            assertThat(analysisCalls).hasValue(0);
        }
    }

    @Test
    void applicationBoundaryIdleTimeoutDoesNotActAsATotalStreamDeadline() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                new PrivacyResponseInspectionLimits(20, 1000, 1000, Duration.ofMillis(200))
        );
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.interval(Duration.ofMillis(30))
                .take(10)
                .map(index -> response("safe-" + index)));

        try (PrivacySession session = service.openSession()) {
            List<ChatClientResponse> responses = protectStreamAtApplicationBoundary(
                    advisor, session.handle(), chain
            ).collectList().block();

            assertThat(responses).hasSize(10);
        }
    }

    @Test
    void adviseStreamPreservesEveryGenerationMediaAndMetadataWhileBuffering() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        Media firstMedia = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(new byte[]{1})
                .name("first")
                .build();
        Media secondMedia = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(new byte[]{2})
                .name("second")
                .build();
        ChatGenerationMetadata firstGenerationMetadata = ChatGenerationMetadata.builder()
                .metadata("chunk-one", 1)
                .build();
        ChatGenerationMetadata finalGenerationMetadata = ChatGenerationMetadata.builder()
                .metadata("chunk-two", 2)
                .finishReason("stop")
                .build();
        ChatResponseMetadata firstResponseMetadata = ChatResponseMetadata.builder()
                .id("response-id")
                .model("model-id")
                .usage(new DefaultUsage(3, 0, 3))
                .keyValue("provider-one", "one")
                .build();
        ChatResponseMetadata finalResponseMetadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(3, 4, 7))
                .keyValue("provider-two", "two")
                .build();
        when(chain.nextStream(any())).thenReturn(Flux.just(
                new ChatClientResponse(new ChatResponse(List.of(
                        new Generation(AssistantMessage.builder()
                                .content("Ali")
                                .properties(Map.of("message-one", 1))
                                .media(List.of(firstMedia))
                                .build(), firstGenerationMetadata),
                        new Generation(new AssistantMessage("Bo"), firstGenerationMetadata)
                ), firstResponseMetadata), Map.of("first-context", true)),
                new ChatClientResponse(new ChatResponse(List.of(
                        new Generation(AssistantMessage.builder()
                                .content("ce")
                                .properties(Map.of("message-two", 2))
                                .media(List.of(secondMedia))
                                .build(), finalGenerationMetadata),
                        new Generation(new AssistantMessage("b"), finalGenerationMetadata)
                ), finalResponseMetadata), Map.of("second-context", true))
        ));

        try (PrivacySession session = service.openSession()) {
            List<ChatClientResponse> results = protectStreamAtApplicationBoundary(
                    advisor, session.handle(), chain
            ).collectList().block();

            assertThat(results).hasSize(2);
            assertThat(streamText(results, 0))
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern());
            assertThat(streamText(results, 1))
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern());
            assertThat(results.get(0).chatResponse().getResults().get(0).getOutput().getMedia())
                    .containsExactly(firstMedia);
            assertThat(results.get(1).chatResponse().getResults().get(0).getOutput().getMedia())
                    .containsExactly(secondMedia);
            assertThat(results.get(0).chatResponse().getResults().get(0).getOutput().getMetadata())
                    .containsOnly(
                            Map.entry("message-one", 1),
                            Map.entry("messageType", MessageType.ASSISTANT)
                    );
            assertThat(results.get(1).chatResponse().getResults().get(0).getOutput().getMetadata())
                    .containsOnly(
                            Map.entry("message-two", 2),
                            Map.entry("messageType", MessageType.ASSISTANT)
                    );
            assertThat(results.get(0).chatResponse().getResults().get(0).getMetadata())
                    .isSameAs(firstGenerationMetadata);
            assertThat(results.get(1).chatResponse().getResults().get(0).getMetadata())
                    .isSameAs(finalGenerationMetadata);
            assertThat(results.get(0).chatResponse().getMetadata()).isSameAs(firstResponseMetadata);
            assertThat(results.get(1).chatResponse().getMetadata()).isSameAs(finalResponseMetadata);
            assertThat(results.get(0).context()).containsExactly(Map.entry("first-context", true));
            assertThat(results.get(1).context()).containsExactly(Map.entry("second-context", true));
        }
    }

    @Test
    void adviseStreamAggregatesAndProtectsSplitProviderTextMetadata() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(
                response(List.of(new Generation(
                        AssistantMessage.builder()
                                .properties(Map.of("reasoningContent", "Ali", "provider", "one"))
                                .build(),
                        ChatGenerationMetadata.builder().metadata("thinking", "Ali").build()
                ))),
                response(List.of(new Generation(
                        AssistantMessage.builder()
                                .properties(Map.of("reasoningContent", "ce", "provider", "two"))
                                .build(),
                        ChatGenerationMetadata.builder()
                                .finishReason("stop")
                                .metadata("thinking", "ce")
                                .build()
                )))
        ));

        try (PrivacySession session = service.openSession()) {
            List<ChatClientResponse> results = protectStreamAtApplicationBoundary(
                    advisor,
                    session.handle(),
                    chain
            ).collectList().block();

            assertThat(results).hasSize(2);
            Generation first = results.get(0).chatResponse().getResult();
            Generation terminal = results.get(1).chatResponse().getResult();
            assertThat(first.getOutput().getMetadata().get("reasoningContent")).isEqualTo("");
            assertThat((Object) first.getMetadata().get("thinking")).isEqualTo("");
            assertThat(terminal.getOutput().getMetadata().get("reasoningContent").toString())
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern());
            assertThat(terminal.getMetadata().get("thinking").toString())
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern());
            assertThat(terminal.getOutput().getMetadata().get("provider")).isEqualTo("two");
            assertThat(terminal.getMetadata().getFinishReason()).isEqualTo("stop");
        }
    }

    @Test
    void adviseStreamKeepsGoogleThoughtContentOutOfTheAnswerChannel() {
        assertThoughtChannelIsProtectedSeparately("isThought");
    }

    @Test
    void adviseStreamKeepsAnthropicThinkingContentOutOfTheAnswerChannel() {
        assertThoughtChannelIsProtectedSeparately("thinking");
    }

    @Test
    void adviseStreamAggregatesAndProtectsOfficialDeepSeekReasoningContent() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(
                response(List.of(new Generation(new DeepSeekAssistantMessage.Builder()
                        .reasoningContent("Ali")
                        .prefix(true)
                        .build()))),
                response(List.of(new Generation(new DeepSeekAssistantMessage.Builder()
                        .reasoningContent("ce")
                        .prefix(true)
                        .build())))
        ));

        try (PrivacySession session = service.openSession()) {
            List<ChatClientResponse> results = protectStreamAtApplicationBoundary(
                    advisor,
                    session.handle(),
                    chain
            ).collectList().block();

            DeepSeekAssistantMessage first = (DeepSeekAssistantMessage)
                    results.get(0).chatResponse().getResult().getOutput();
            DeepSeekAssistantMessage terminal = (DeepSeekAssistantMessage)
                    results.get(1).chatResponse().getResult().getOutput();
            assertThat(first.getReasoningContent()).isNull();
            assertThat(terminal.getReasoningContent())
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern());
            assertThat(terminal.getPrefix()).isTrue();
        }
    }

    @Test
    void adviseStreamCountsProviderTextMetadataAgainstTheCharacterLimit() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                new PrivacyResponseInspectionLimits(10, 4, 100, Duration.ofSeconds(1))
        );
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(response(List.of(new Generation(
                AssistantMessage.builder()
                        .properties(Map.of("reasoningContent", "Alice"))
                        .build()
        )))));

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(
                    advisor,
                    session.handle(),
                    chain
            ).collectList().block())
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class, failure ->
                            assertThat(failure.code())
                                    .isEqualTo(
                                            PrivacyFailureCode.RESPONSE_INSPECTION_LIMIT_EXCEEDED
                                    ));
        }
    }

    @Test
    void adviseStreamCorrelatesReorderedChoicesByProviderIndex() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ChatGenerationMetadata choiceZero = ChatGenerationMetadata.builder()
                .build();
        ChatGenerationMetadata choiceOne = ChatGenerationMetadata.builder()
                .build();
        when(chain.nextStream(any())).thenReturn(Flux.just(
                response(List.of(
                        new Generation(AssistantMessage.builder()
                                .content("Ali")
                                .properties(Map.of("index", 0))
                                .build(), choiceZero),
                        new Generation(AssistantMessage.builder()
                                .content("Bo")
                                .properties(Map.of("index", 1))
                                .build(), choiceOne)
                )),
                response(List.of(
                        new Generation(AssistantMessage.builder()
                                .content("b")
                                .properties(Map.of("index", 1))
                                .build(), choiceOne),
                        new Generation(AssistantMessage.builder()
                                .content("ce")
                                .properties(Map.of("index", 0))
                                .build(), choiceZero)
                ))
        ));

        try (PrivacySession session = service.openSession()) {
            List<ChatClientResponse> results = protectStreamAtApplicationBoundary(
                    advisor, session.handle(), chain
            ).collectList().block();

            assertThat(results).hasSize(2);
            assertThat(results.get(0).chatResponse().getResults())
                    .allSatisfy(generation -> assertThat(generation.getOutput().getText()).isNull());
            assertThat(results.get(1).chatResponse().getResults().get(0).getOutput().getText())
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern());
            assertThat(results.get(1).chatResponse().getResults().get(1).getOutput().getText())
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern());
        }
    }

    @Test
    void adviseStreamFailsClosedWhenChoiceIndexSourcesConflict() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .metadata("index", 0)
                .build();
        when(chain.nextStream(any())).thenReturn(Flux.just(response(List.of(new Generation(
                AssistantMessage.builder()
                        .content("Alice")
                        .properties(Map.of("index", 1))
                        .build(),
                generationMetadata
        )))));

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(advisor, session.handle(), chain)
                    .collectList()
                    .block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasFieldOrPropertyWithValue("code", PrivacyFailureCode.TRANSFORMATION_CONFLICT);
        }
    }

    @Test
    void adviseStreamFailsClosedWhenChoiceIndexIsOnlyPartiallyAvailable() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(
                response(List.of(new Generation(AssistantMessage.builder()
                        .content("Ali")
                        .properties(Map.of("index", 0))
                        .build()))),
                response("ce")
        ));

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(advisor, session.handle(), chain)
                    .collectList()
                    .block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasFieldOrPropertyWithValue("code", PrivacyFailureCode.TRANSFORMATION_CONFLICT);
        }
    }

    @Test
    void adviseStreamIgnoresNonNumericIndexMetadataAndUsesStablePosition() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(
                response(List.of(new Generation(AssistantMessage.builder()
                        .content("Ali")
                        .properties(Map.of("index", "first-frame"))
                        .build()))),
                response(List.of(new Generation(AssistantMessage.builder()
                        .content("ce")
                        .properties(Map.of("index", "second-frame"))
                        .build())))
        ));

        try (PrivacySession session = service.openSession()) {
            List<ChatClientResponse> results = protectStreamAtApplicationBoundary(
                    advisor,
                    session.handle(),
                    chain
            ).collectList().block();

            assertThat(results.get(0).chatResponse().getResult().getOutput().getText()).isNull();
            assertThat(results.get(1).chatResponse().getResult().getOutput().getText())
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern());
            assertThat(results.get(1).chatResponse().getResult().getOutput().getMetadata())
                    .containsEntry("index", "second-frame");
        }
    }

    @Test
    void adviseStreamProtectsToolArgumentsSplitAcrossChunks() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(
                response(List.of(new Generation(AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "lookup",
                                "{\"name\":\"Ali"
                        )))
                        .build()))),
                response(List.of(new Generation(AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "call-1",
                                "function",
                                "lookup",
                                "ce\"}"
                        )))
                        .build())))
        ));

        try (PrivacySession session = service.openSession()) {
            List<ChatClientResponse> results = protectStreamAtApplicationBoundary(
                    advisor, session.handle(), chain
            ).collectList().block();

            assertThat(results).hasSize(2);
            assertThat(results.get(0).chatResponse().getResult().getOutput().getToolCalls()).isEmpty();
            assertThat(results.get(1).chatResponse().getResult().getOutput().getToolCalls())
                    .singleElement()
                    .extracting(AssistantMessage.ToolCall::arguments)
                    .asString()
                    .contains("[[PII_PERSON_")
                    .doesNotContain("Alice");
        }
    }

    @Test
    void adviseStreamFailsClosedForUnidentifiedMultipleToolCalls() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(
                response(List.of(new Generation(AssistantMessage.builder()
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("", "function", "lookup", "Ali"),
                                new AssistantMessage.ToolCall("", "function", "lookup", "Bo")
                        ))
                        .build()))),
                response(List.of(new Generation(AssistantMessage.builder()
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall("", "function", "lookup", "b"),
                                new AssistantMessage.ToolCall("", "function", "lookup", "ce")
                        ))
                        .build())))
        ));
        AtomicInteger emittedResponses = new AtomicInteger();

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(advisor, session.handle(), chain)
                    .doOnNext(ignored -> emittedResponses.incrementAndGet())
                    .collectList()
                    .block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasFieldOrPropertyWithValue("code", PrivacyFailureCode.TRANSFORMATION_CONFLICT);
        }
        assertThat(emittedResponses).hasValue(0);
    }

    @Test
    void adviseStreamProtectsUnidentifiedMultipleToolCallsInOneFrame() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(response(List.of(new Generation(
                AssistantMessage.builder()
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall(
                                        "", "function", "lookup", "{\"owner\":\"Alice\"}"
                                ),
                                new AssistantMessage.ToolCall(
                                        "", "function", "audit", "{\"reviewer\":\"Alice\"}"
                                )
                        ))
                        .build()
        )))));

        try (PrivacySession session = service.openSession()) {
            List<AssistantMessage.ToolCall> toolCalls = protectStreamAtApplicationBoundary(
                    advisor,
                    session.handle(),
                    chain
            ).single().block().chatResponse().getResult().getOutput().getToolCalls();

            assertThat(toolCalls).hasSize(2)
                    .allSatisfy(toolCall -> assertThat(toolCall.arguments())
                            .doesNotContain("Alice")
                            .contains("[[PII_PERSON_"));
            assertThat(toolCalls).extracting(AssistantMessage.ToolCall::name)
                    .containsExactly("lookup", "audit");
        }
    }

    @Test
    void adviseStreamFailsClosedForUnknownAssistantSubtypeBeforeReplayingFrames() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(response(List.of(new Generation(
                new StreamProviderAssistantMessage(null, "Alice is hidden")
        )))));
        AtomicInteger emittedResponses = new AtomicInteger();

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(
                    advisor,
                    session.handle(),
                    chain
            ).doOnNext(ignored -> emittedResponses.incrementAndGet()).collectList().block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasFieldOrPropertyWithValue("code", PrivacyFailureCode.TRANSFORMATION_CONFLICT)
                    .hasMessage("Unsupported Spring AI Message implementation")
                    .hasMessageNotContaining("Alice");
        }
        assertThat(emittedResponses).hasValue(0);
    }

    @Test
    void adviseStreamCancelsInfiniteSourceAtFrameLimitBeforeEmitting() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                new PrivacyResponseInspectionLimits(2, 100, 100, Duration.ofSeconds(1))
        );
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        AtomicInteger generatedFrames = new AtomicInteger();
        when(chain.nextStream(any())).thenReturn(Flux.<ChatClientResponse>generate(sink -> {
            generatedFrames.incrementAndGet();
            sink.next(response("safe"));
        }));
        AtomicInteger emittedResponses = new AtomicInteger();

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(advisor, session.handle(), chain)
                    .doOnNext(ignored -> emittedResponses.incrementAndGet())
                    .collectList()
                    .block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasFieldOrPropertyWithValue(
                            "code",
                            PrivacyFailureCode.RESPONSE_INSPECTION_LIMIT_EXCEEDED
                    )
                    .hasMessage("Model response exceeded the configured privacy inspection limit");
        }
        assertThat(generatedFrames).hasValue(3);
        assertThat(emittedResponses).hasValue(0);
    }

    @Test
    void adviseStreamEnforcesCharacterAndMediaLimits() {
        PrivacyService service = privacyService();
        StreamAdvisorChain characterChain = mock(StreamAdvisorChain.class);
        when(characterChain.nextStream(any())).thenReturn(Flux.just(response("Alice")));
        PrivacyOutputAdvisor characterAdvisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                new PrivacyResponseInspectionLimits(10, 4, 100, Duration.ofSeconds(1))
        );
        StreamAdvisorChain controlFieldChain = mock(StreamAdvisorChain.class);
        when(controlFieldChain.nextStream(any())).thenReturn(Flux.just(response(List.of(new Generation(
                AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "12345", "function", "lookup", ""
                        )))
                        .build()
        )))));
        PrivacyOutputAdvisor controlFieldAdvisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                new PrivacyResponseInspectionLimits(10, 4, 100, Duration.ofSeconds(1))
        );
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1, 2}))
                .build();
        StreamAdvisorChain mediaChain = mock(StreamAdvisorChain.class);
        when(mediaChain.nextStream(any())).thenReturn(Flux.just(response(List.of(new Generation(
                AssistantMessage.builder().media(List.of(media)).build()
        )))));
        PrivacyOutputAdvisor mediaAdvisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                new PrivacyResponseInspectionLimits(10, 100, 1, Duration.ofSeconds(1))
        );
        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(
                    characterAdvisor, session.handle(), characterChain
            )
                    .collectList()
                    .block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasFieldOrPropertyWithValue(
                            "code",
                            PrivacyFailureCode.RESPONSE_INSPECTION_LIMIT_EXCEEDED
                    );
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(
                    controlFieldAdvisor, session.handle(), controlFieldChain
            ).collectList().block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasFieldOrPropertyWithValue(
                            "code",
                            PrivacyFailureCode.RESPONSE_INSPECTION_LIMIT_EXCEEDED
                    );
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(
                    mediaAdvisor, session.handle(), mediaChain
            )
                    .collectList()
                    .block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasFieldOrPropertyWithValue(
                            "code",
                            PrivacyFailureCode.RESPONSE_INSPECTION_LIMIT_EXCEEDED
                    );
        }
    }

    @Test
    void adviseStreamFailsClosedForUrlMediaData() throws MalformedURLException {
        PrivacyService service = privacyService();
        Media remoteMedia = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(URI.create("https://example.invalid/media").toURL())
                .build();
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(response(List.of(new Generation(
                AssistantMessage.builder().media(List.of(remoteMedia)).build()
        )))));
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                new PrivacyResponseInspectionLimits(10, 100, 100, Duration.ofSeconds(1))
        );

        // URL-valued media data cannot be locally size-accounted without dereferencing it,
        // so this representation fails closed without network access.
        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(
                    advisor, session.handle(), chain
            ).collectList().block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasFieldOrPropertyWithValue(
                            "code",
                            PrivacyFailureCode.RESPONSE_INSPECTION_LIMIT_EXCEEDED
                    );
        }
    }

    @Test
    void responseInspectionLimitsRejectNonPositiveValues() {
        assertThatThrownBy(() -> new PrivacyResponseInspectionLimits(0, 1, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrivacyResponseInspectionLimits(1, 0, 1, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrivacyResponseInspectionLimits(1, 1, 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrivacyResponseInspectionLimits(1, 1, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void adviseStreamTimesOutAStalledSourceBeforeEmitting() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.TOKENIZE,
                "blocked",
                new PrivacyResponseInspectionLimits(10, 100, 100, Duration.ofMillis(20))
        );
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.<ChatClientResponse>never());
        AtomicInteger emittedResponses = new AtomicInteger();

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(advisor, session.handle(), chain)
                    .doOnNext(ignored -> emittedResponses.incrementAndGet())
                    .collectList()
                    .block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasFieldOrPropertyWithValue("code", PrivacyFailureCode.STREAM_TIMEOUT)
                    .hasMessage("Streaming response exceeded the configured privacy idle timeout");
        }
        assertThat(emittedResponses).hasValue(0);
    }

    @Test
    void adviseStreamFailsClosedWhenChoicesCannotBeCorrelated() {
        PrivacyService service = TestPrivacyServices.privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(
                response(List.of(
                        new Generation(new AssistantMessage("Ali")),
                        new Generation(new AssistantMessage("Bo"))
                )),
                response("ce")
        ));
        AtomicInteger emittedResponses = new AtomicInteger();

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(advisor, session.handle(), chain)
                    .doOnNext(ignored -> emittedResponses.incrementAndGet())
                    .collectList()
                    .block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasFieldOrPropertyWithValue("code", PrivacyFailureCode.TRANSFORMATION_CONFLICT)
                    .hasMessage("Streaming response choices cannot be correlated safely");
        }
        assertThat(emittedResponses).hasValue(0);
    }

    @Test
    void adviseStreamBlocksBeforeAnyRawChunkReachesTheSubscriber() {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(
                service,
                PrivacyOutputAction.BLOCK,
                "blocked"
        );
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.just(
                response("Ali"),
                response("ce is available")
        ));
        AtomicInteger emittedResponses = new AtomicInteger();

        try (PrivacySession session = service.openSession()) {
            assertThatThrownBy(() -> protectStreamAtApplicationBoundary(advisor, session.handle(), chain)
                    .doOnNext(ignored -> emittedResponses.incrementAndGet())
                    .collectList()
                    .block())
                    .isInstanceOf(PrivacyGuardrailException.class)
                    .hasMessage("blocked");
            assertThat(service.isSessionActive(session.handle())).isTrue();
        }
        assertThat(emittedResponses).hasValue(0);
        assertThat(service.activeSessionCount()).isZero();
    }

    private void assertThoughtChannelIsProtectedSeparately(String marker) {
        PrivacyService service = privacyService();
        PrivacyOutputAdvisor advisor = new PrivacyOutputAdvisor(service);
        StreamAdvisorChain chain = mock(StreamAdvisorChain.class);
        Map<String, Object> answerMetadata = marker.equals("isThought")
                ? Map.of(marker, Boolean.FALSE)
                : Map.of();
        when(chain.nextStream(any())).thenReturn(Flux.just(
                response(List.of(new Generation(AssistantMessage.builder()
                        .content("Ali")
                        .properties(Map.of(marker, Boolean.TRUE))
                        .build()))),
                response(List.of(new Generation(AssistantMessage.builder()
                        .content("ce")
                        .properties(Map.of(marker, Boolean.TRUE))
                        .build()))),
                response(List.of(new Generation(AssistantMessage.builder()
                        .content("safe answer")
                        .properties(answerMetadata)
                        .build())))
        ));

        try (PrivacySession session = service.openSession()) {
            List<ChatClientResponse> results = protectStreamAtApplicationBoundary(
                    advisor,
                    session.handle(),
                    chain
            ).collectList().block();

            AssistantMessage firstThought = results.get(0).chatResponse().getResult().getOutput();
            AssistantMessage terminalThought = results.get(1).chatResponse().getResult().getOutput();
            AssistantMessage answer = results.get(2).chatResponse().getResult().getOutput();
            assertThat(firstThought.getText()).isNull();
            assertThat(terminalThought.getText())
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PERSON").pattern());
            assertThat(terminalThought.getMetadata()).containsEntry(marker, Boolean.TRUE);
            assertThat(answer.getText()).isEqualTo("safe answer").doesNotContain("Alice");
        }
    }

    private PrivacyService privacyService() {
        return privacyService(new AtomicInteger());
    }

    private PrivacyService privacyService(AtomicInteger analysisCalls) {
        PiiAnalyzer analyzer = (text, options) -> {
            analysisCalls.incrementAndGet();
            int index = text.indexOf("Alice");
            if (index < 0) {
                return List.of();
            }
            return List.of(new PiiSpan("PERSON", index, index + 5, 0.95));
        };
        return new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());
    }

    private ChatClientRequest request(PrivacyContextHandle handle) {
        return new ChatClientRequest(
                new Prompt("hello"),
                Map.of(PrivacyRequestContextSupport.CONTEXT_HANDLE, handle)
        );
    }

    private Flux<ChatClientResponse> protectStreamAtApplicationBoundary(
            PrivacyOutputAdvisor advisor,
            PrivacyContextHandle handle,
            StreamAdvisorChain chain
    ) {
        return advisor.protectAtApplicationBoundary(
                handle,
                advisor.adviseStream(request(handle), chain)
        );
    }

    private ChatClientResponse response(String text) {
        return response(List.of(new Generation(new AssistantMessage(text))));
    }

    private ChatClientResponse response(List<Generation> generations) {
        return new ChatClientResponse(new ChatResponse(generations), Map.of());
    }

    private String streamText(List<ChatClientResponse> responses, int generationIndex) {
        StringBuilder text = new StringBuilder();
        for (ChatClientResponse response : responses) {
            String chunk = response.chatResponse().getResults().get(generationIndex).getOutput().getText();
            if (chunk != null) {
                text.append(chunk);
            }
        }
        return text.toString();
    }

    private static final class StreamProviderAssistantMessage extends AssistantMessage {

        @SuppressWarnings("unused")
        private final String hiddenReasoning;

        private StreamProviderAssistantMessage(String content, String hiddenReasoning) {
            super(content, Map.of(), List.of(), List.of());
            this.hiddenReasoning = hiddenReasoning;
        }
    }
}
