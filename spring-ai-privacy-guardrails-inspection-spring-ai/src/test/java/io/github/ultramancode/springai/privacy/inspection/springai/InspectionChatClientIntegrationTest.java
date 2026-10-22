package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionPolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionTestSupport.RecordingChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.MimeTypeUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.StepVerifierOptions;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionChatClientIntegrationTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

    private InspectionChatClientConfigurer attackBlockingInspectionConfigurer() {
        InspectionRule rule = InspectionRule.literal("attack-rule", InspectionFinding.Category.PROMPT_INJECTION, "attack");
        ContentInspector inspector = new RuleBasedContentInspector("application-rules", List.of(rule));
        return new InspectionChatClientConfigurer(new InspectionService(List.of(inspector)));
    }

    private ChatClient createInspectionClient(RecordingChatModel model) {
        return attackBlockingInspectionConfigurer().configure(ChatClient.builder(model)).build();
    }

    private ToolCallback lookupToolReturning(String toolResult) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("lookup")
                        .description("Lookup a fixture")
                        .inputSchema("""
                                {}
                                """.strip())
                        .build();
            }

            public String call(String arguments) {
                return toolResult;
            }
        };
    }

    @Test
    void allowsSafeInputAndBlocksAttackBeforeModelCall() {
        RecordingChatModel model = new RecordingChatModel();
        ChatClient client = createInspectionClient(model);
        assertThat(client.prompt().user("hello").call().content()).isEqualTo("done");
        assertThatThrownBy(() -> client.prompt().user("attack").call().content())
                .isInstanceOf(InspectionBlockedException.class);
        assertThat(model.modelCallCount).hasValue(1);
    }

    @Test
    void allowsSafeInputAndBlocksAttackBeforeModelCallWhenStreaming() {
        RecordingChatModel model = new RecordingChatModel();
        Flux<String> publisher = createInspectionClient(model).prompt().user("attack").stream().content();
        assertThat(model.modelCallCount).hasValue(0);
        StepVerifier.create(publisher)
                .expectError(InspectionBlockedException.class)
                .verify(TEST_TIMEOUT);
        assertThat(model.modelCallCount).hasValue(0);
        StepVerifier.create(createInspectionClient(model).prompt().user("hello").stream().content())
                .expectNext("done")
                .expectComplete()
                .verify(TEST_TIMEOUT);
        assertThat(model.modelCallCount).hasValue(1);
    }

    @ParameterizedTest(name = "streaming={0}")
    @ValueSource(booleans = {false, true})
    void blocksAttackToolResultBeforeSecondModelCall(boolean streaming) {
        RecordingChatModel model = new RecordingChatModel();
        model.lookupToolCallEnabled = true;
        ChatClient client =
                attackBlockingInspectionConfigurer()
                        .configure(ChatClient.builder(model))
                        .defaultTools(lookupToolReturning("attack"))
                        .build();
        if (streaming) {
            StepVerifier.create(client.prompt().user("lookup").stream().content())
                    .expectError(InspectionBlockedException.class)
                    .verify(TEST_TIMEOUT);
        } else {
            assertThatThrownBy(() -> client.prompt().user("lookup").call().content())
                    .isInstanceOf(InspectionBlockedException.class);
        }
        assertThat(model.modelCallCount).hasValue(1);
    }

    @Test
    void allowsSafeToolResultBeforeSecondModelCall() {
        RecordingChatModel model = new RecordingChatModel();
        model.lookupToolCallEnabled = true;
        ChatClient client =
                attackBlockingInspectionConfigurer()
                        .configure(ChatClient.builder(model))
                        .defaultTools(lookupToolReturning("safe result"))
                        .build();
        assertThat(client.prompt().user("lookup").call().content()).isEqualTo("done");
        assertThat(model.modelCallCount).hasValue(2);
    }

    @Test
    void blocksAttackInSystemMessageBeforeModelCall() {
        RecordingChatModel model = new RecordingChatModel();
        ChatClient client = createInspectionClient(model);
        Prompt prompt = new Prompt(List.of(new SystemMessage("attack"), new UserMessage("hello")));
        assertThatThrownBy(() -> client.prompt(prompt).call().content())
                .isInstanceOf(InspectionBlockedException.class);
        assertThat(model.modelCallCount).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesMessageOrderAndSeparateToolResponseSegments(boolean streaming) {
        AtomicReference<InspectionRequest> recordedInspectionRequest = new AtomicReference<>();
        ContentInspector inspector = new ContentInspector() {
            @Override
            public String inspectorId() {
                return "message-recorder";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                recordedInspectionRequest.set(request);
                return InspectionResult.completed(
                        request.segments().stream().map(ContentSegment::id).collect(Collectors.toSet()),
                        List.of());
            }
        };
        RecordingChatModel model = new RecordingChatModel();
        ChatClient client = new InspectionChatClientConfigurer(new InspectionService(List.of(inspector)))
                .configure(ChatClient.builder(model)).build();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("instructions"),
                new UserMessage("first user text"),
                new AssistantMessage("assistant text"),
                new UserMessage("second user text"),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "firstTool", "first tool result"),
                        new ToolResponseMessage.ToolResponse("call-2", "secondTool", "second tool result")))
                        .build()));

        if (streaming) {
            StepVerifier.create(client.prompt(prompt).stream().content())
                    .expectNext("done")
                    .expectComplete()
                    .verify(TEST_TIMEOUT);
        } else {
            assertThat(client.prompt(prompt).call().content()).isEqualTo("done");
        }

        List<ContentSegment> inspectedInputSegments = recordedInspectionRequest.get().segments();
        assertThat(inspectedInputSegments).extracting(ContentSegment::role).containsExactly(
                ContentSegment.Role.SYSTEM, ContentSegment.Role.USER, ContentSegment.Role.ASSISTANT,
                ContentSegment.Role.USER, ContentSegment.Role.TOOL, ContentSegment.Role.TOOL);
        assertThat(inspectedInputSegments).extracting(ContentSegment::text).containsExactly(
                "instructions", "first user text", "assistant text", "second user text",
                "first tool result", "second tool result");
        assertThat(inspectedInputSegments).extracting(ContentSegment::id).doesNotHaveDuplicates();
        assertThat(inspectedInputSegments).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.UNKNOWN));
        assertThat(model.modelCallCount).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsUnsupportedMessageSubclassesAndMediaBeforeModelCall(boolean streaming) {
        Media media = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] {1}).build();
        Map<String, Message> unsupportedMessages = new LinkedHashMap<>();
        unsupportedMessages.put("user message subclass", new UserMessage("hello") {});
        unsupportedMessages.put("user media", UserMessage.builder().text("hello").media(List.of(media)).build());
        unsupportedMessages.put("assistant media", AssistantMessage.builder().content("hello").media(List.of(media)).build());

        for (Map.Entry<String, Message> entry : unsupportedMessages.entrySet()) {
            String description = entry.getKey();
            RecordingChatModel model = new RecordingChatModel();
            ChatClient.ChatClientRequestSpec request = createInspectionClient(model).prompt(new Prompt(List.of(entry.getValue())));
            if (streaming) {
                StepVerifierOptions verifierOptions = StepVerifierOptions.create().scenarioName(description);
                StepVerifier.create(request.stream().content(), verifierOptions)
                        .expectErrorMatches(error -> error instanceof InspectionException failure
                                && failure.code() == InspectionFailureCode.UNSUPPORTED_CONTENT)
                        .verify(TEST_TIMEOUT);
            } else {
                assertThatThrownBy(() -> request.call().content())
                        .as(description)
                        .isInstanceOfSatisfying(InspectionException.class, failure ->
                                assertThat(failure.code()).isEqualTo(InspectionFailureCode.UNSUPPORTED_CONTENT));
            }
            assertThat(model.modelCallCount).as(description).hasValue(0);
        }
    }

    @Test
    void blocksAttackInRetrievedContentBeforeModelCall() {
        RecordingChatModel model = new RecordingChatModel();
        CallAdvisor retrievalAdvisor = new CallAdvisor() {
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                Prompt promptWithRetrievedContent = new Prompt(List.of(
                        new SystemMessage("retrieved attack"),
                        new UserMessage("hello")));
                return chain.nextCall(request.mutate().prompt(promptWithRetrievedContent).build());
            }

            public int getOrder() {
                // Run retrieval before the inspection boundary.
                return 100;
            }

            public String getName() {
                return "fixture-rag";
            }
        };
        ChatClient client = createInspectionClient(model);
        assertThatThrownBy(() -> client.prompt().user("hello").advisors(retrievalAdvisor).call().content())
                .isInstanceOf(InspectionBlockedException.class);
        assertThat(model.modelCallCount).hasValue(0);
    }

    @Test
    void permitsApplicationOwnedFormattingWithoutInspectingIt() {
        RecordingChatModel model = new RecordingChatModel();
        ChatClient client = createInspectionClient(model);
        String key = ChatClientAttributes.OUTPUT_FORMAT.getKey();
        assertThat(client.prompt().user("hello").advisors(a -> a.param(key, "attack"))
                .call().content()).isEqualTo("done");
        StepVerifier.create(client.prompt().user("hello").advisors(a -> a.param(key, "attack"))
                .stream().content()).expectNext("done").verifyComplete();
        assertThat(model.modelCallCount).hasValue(2);
    }

    @Test
    void rejectsRequestAdvisorAfterModelAdvisorBeforeModelCall() {
        RecordingChatModel model = new RecordingChatModel();
        CallAdvisor lateRequestAdvisor = new CallAdvisor() {
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                return chain.nextCall(request);
            }

            public int getOrder() {
                // ChatModelCallAdvisor also uses Integer.MAX_VALUE.
                // In this test, Spring AI's default chain places lateRequestAdvisor after ChatModelCallAdvisor.
                return Integer.MAX_VALUE;
            }

            public String getName() {
                return "late";
            }
        };
        assertThatThrownBy(() -> createInspectionClient(model).prompt().user("hello").advisors(lateRequestAdvisor).call().content())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("that advisor must be last");
        assertThat(model.modelCallCount).hasValue(0);
    }

    @Test
    void allowsEarlierAdvisorToWrapInspectedCall() {
        RecordingChatModel model = new RecordingChatModel();
        AtomicInteger wrappedCallCount = new AtomicInteger();
        CallAdvisor wrappingAdvisor = new CallAdvisor() {
            public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
                ChatClientResponse result = chain.nextCall(request);
                wrappedCallCount.incrementAndGet();
                return result;
            }

            public int getOrder() {
                return 0;
            }

            public String getName() {
                return "wrapping-advisor";
            }
        };
        InspectionChatClientConfigurer inspectionConfigurer = attackBlockingInspectionConfigurer();
        ChatClient.Builder builder = inspectionConfigurer.configure(ChatClient.builder(model)).defaultAdvisors(wrappingAdvisor);
        assertThat(builder.build().prompt().user("hello").call().content()).isEqualTo("done");
        assertThat(wrappedCallCount).hasValue(1);
    }

    @Test
    void cancellationDuringInspectionPreventsModelCallEvenWithFailOpen() throws Exception {
        CountDownLatch inspectionStarted = new CountDownLatch(1);
        CountDownLatch inspectionInterrupted = new CountDownLatch(1);
        CompletableFuture<InspectionException> inspectionFailure = new CompletableFuture<>();
        ContentInspector blockingInspector = new ContentInspector() {
            @Override
            public String inspectorId() {
                return "blocking-inspector";
            }

            public boolean requiresPrivacyProcessedContent() {
                return false;
            }

            public InspectionResult inspect(InspectionRequest request) {
                inspectionStarted.countDown();
                try {
                    // Wait for cancellation to interrupt this inspector.
                    new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    // Restore the interrupt flag cleared by await() so cancellation checks can detect it.
                    Thread.currentThread().interrupt();
                    inspectionInterrupted.countDown();
                }
                InspectionRequest.checkInterrupted();
                return InspectionResult.failed(InspectionFailureCode.TIMEOUT);
            }
        };
        InspectionObserver inspectionObserver = new InspectionObserver() {
            @Override
            public void onInspection(InspectionReport report, InspectionExecution execution) {}

            @Override
            public void onFailure(InspectionException failure, InspectionExecution execution) {
                inspectionFailure.complete(failure);
            }
        };
        RecordingChatModel model = new RecordingChatModel();
        InspectionService inspectionService = new InspectionService(List.of(blockingInspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
        InspectionChatClientConfigurer inspectionConfigurer = new InspectionChatClientConfigurer(
                inspectionService, InspectionLimits.defaults(),
                PrivacyProcessingStatusResolver.unknown(), inspectionObserver);
        ChatClient client = inspectionConfigurer.configure(ChatClient.builder(model)).build();
        Disposable streamSubscription = client.prompt().user("hello").stream().content().subscribe();
        try {
            assertThat(inspectionStarted.await(5, TimeUnit.SECONDS)).isTrue();
            streamSubscription.dispose();
            assertThat(inspectionInterrupted.await(5, TimeUnit.SECONDS)).isTrue();
            InspectionException cancellationException = inspectionFailure.get(5, TimeUnit.SECONDS);
            assertThat(cancellationException.code()).isEqualTo(InspectionFailureCode.CANCELLED);
            assertThat(model.modelCallCount).hasValue(0);
        } finally {
            streamSubscription.dispose();
        }
    }
}
