package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionPolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.MimeTypeUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionChatClientIntegrationTest {

    private InspectionChatClientConfigurer configurer() {
        return new InspectionChatClientConfigurer(
                new InspectionService(
                        List.of(
                                new RuleBasedContentInspector(
                                        List.of(InspectionRule.literal("attack", "attack"))))));
    }

    private ChatClient client(RecordingModel model) {
        return configurer().configure(ChatClient.builder(model)).build();
    }

    static final class RecordingModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        boolean toolCallingEnabled;

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            if (toolCallingEnabled
                    && prompt.getInstructions().stream()
                            .noneMatch(ToolResponseMessage.class::isInstance)) {
                AssistantMessage.ToolCall toolCall =
                        new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}");
                AssistantMessage assistantMessage = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(toolCall))
                        .build();
                return new ChatResponse(List.of(new Generation(assistantMessage)));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }
    }

    private ToolCallback tool(String response) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("lookup")
                        .description("Lookup a fixture")
                        .inputSchema("{}")
                        .build();
            }

            public String call(String arguments) {
                return response;
            }
        };
    }

    @Test
    void permitsBenignAndBlocksAttackBeforeBusinessCall() {
        RecordingModel model = new RecordingModel();
        ChatClient client = client(model);
        assertThat(client.prompt().user("hello").call().content()).isEqualTo("done");
        assertThatThrownBy(() -> client.prompt().user("attack").call().content())
                .isInstanceOf(InspectionBlockedException.class);
        assertThat(model.calls).hasValue(1);
    }

    @Test
    void streamingIsLazyAndBlocksBeforeSubscriptionToBusinessModel() {
        RecordingModel model = new RecordingModel();
        Flux<String> publisher = client(model).prompt().user("attack").stream().content();
        assertThat(model.calls).hasValue(0);
        StepVerifier.create(publisher)
                .expectError(InspectionBlockedException.class)
                .verify(Duration.ofSeconds(5));
        assertThat(model.calls).hasValue(0);
        StepVerifier.create(client(model).prompt().user("hello").stream().content())
                .expectNext("done")
                .verifyComplete();
        assertThat(model.calls).hasValue(1);
    }

    @Test
    void toolResultIsInspectedBeforeTheSecondModelCall() {
        for (boolean streaming : List.of(false, true)) {
            RecordingModel model = new RecordingModel();
            model.toolCallingEnabled = true;
            ChatClient client =
                    configurer()
                            .configure(ChatClient.builder(model))
                            .defaultTools(tool("attack"))
                            .build();
            if (streaming) {
                StepVerifier.create(client.prompt().user("lookup").stream().content())
                        .expectError(InspectionBlockedException.class)
                        .verify(Duration.ofSeconds(5));
            } else {
                assertThatThrownBy(() -> client.prompt().user("lookup").call().content())
                        .isInstanceOf(InspectionBlockedException.class);
            }
            assertThat(model.calls).hasValue(1);
        }
    }

    @Test
    void benignToolResultAllowsSecondModelCall() {
        RecordingModel model = new RecordingModel();
        model.toolCallingEnabled = true;
        ChatClient client =
                configurer()
                        .configure(ChatClient.builder(model))
                        .defaultTools(tool("benign result"))
                        .build();
        assertThat(client.prompt().user("lookup").call().content()).isEqualTo("done");
        assertThat(model.calls).hasValue(2);
    }

    @Test
    void observesAllMessageRolesNotJustUsers() {
        RecordingModel model = new RecordingModel();
        ChatClient client = client(model);
        assertThatThrownBy(
                        () ->
                                client.prompt(
                                                new Prompt(
                                                        List.of(
                                                                new SystemMessage("attack"),
                                                                new UserMessage("hello"))))
                                        .call()
                                        .content())
                .isInstanceOf(InspectionBlockedException.class);
        assertThat(model.calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesMessageOrderAndSeparatesEachToolResponseWithoutGroupingByRole(boolean streaming) {
        AtomicReference<InspectionRequest> inspected = new AtomicReference<>();
        ContentInspector inspector = new ContentInspector() {
            public boolean requiresPrivacyProcessedContent() { return false; }

            public InspectionResult inspect(InspectionRequest request) {
                inspected.set(request);
                return InspectionResult.completed(
                        request.segments().stream().map(ContentSegment::id).collect(Collectors.toSet()),
                        List.of());
            }
        };
        RecordingModel model = new RecordingModel();
        ChatClient client = new InspectionChatClientConfigurer(new InspectionService(List.of(inspector)))
                .configure(ChatClient.builder(model)).build();
        Prompt prompt = new Prompt(List.of(
                new SystemMessage("instructions"),
                new UserMessage("first user text"),
                new UserMessage("second user text"),
                new AssistantMessage("assistant text"),
                ToolResponseMessage.builder().responses(List.of(
                        new ToolResponseMessage.ToolResponse("call-1", "firstTool", "first tool result"),
                        new ToolResponseMessage.ToolResponse("call-2", "secondTool", "second tool result")))
                        .build()));

        if (streaming) {
            StepVerifier.create(client.prompt(prompt).stream().content())
                    .expectNext("done").verifyComplete();
        } else {
            assertThat(client.prompt(prompt).call().content()).isEqualTo("done");
        }

        List<ContentSegment> segments = inspected.get().segments();
        assertThat(segments).extracting(ContentSegment::role).containsExactly(
                ContentSegment.Role.SYSTEM, ContentSegment.Role.USER, ContentSegment.Role.USER,
                ContentSegment.Role.ASSISTANT, ContentSegment.Role.TOOL, ContentSegment.Role.TOOL);
        assertThat(segments).extracting(ContentSegment::text).containsExactly(
                "instructions", "first user text", "second user text", "assistant text",
                "first tool result", "second tool result");
        assertThat(segments).extracting(ContentSegment::id).doesNotHaveDuplicates();
        assertThat(segments).allSatisfy(segment -> assertThat(segment.privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.UNKNOWN));
        assertThat(model.calls).hasValue(1);
    }

    @ParameterizedTest(name = "{0}, streaming={2}")
    @MethodSource("unsupportedMessages")
    void rejectsUnsupportedContentBeforeBusinessModel(String description, Message message, boolean streaming) {
        RecordingModel model = new RecordingModel();
        ChatClient.ChatClientRequestSpec request = client(model).prompt(new Prompt(List.of(message)));
        if (streaming) {
            StepVerifier.create(request.stream().content())
                    .expectErrorMatches(error -> error instanceof InspectionException failure
                            && failure.failure() == InspectionFailureCode.UNSUPPORTED_CONTENT)
                    .verify(Duration.ofSeconds(5));
        } else {
            assertThatThrownBy(() -> request.call().content()).hasMessageContaining("UNSUPPORTED_CONTENT");
        }
        assertThat(model.calls).hasValue(0);
    }

    private static Stream<Arguments> unsupportedMessages() {
        Media media = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] {1}).build();
        Message custom = new UserMessage("hello") {};
        Message userMedia = UserMessage.builder().text("hello").media(List.of(media)).build();
        Message assistantMedia = AssistantMessage.builder().content("hello").media(List.of(media)).build();
        return Stream.of(
                Arguments.of("custom user message", custom, false),
                Arguments.of("custom user message", custom, true),
                Arguments.of("user media", userMedia, false),
                Arguments.of("user media", userMedia, true),
                Arguments.of("assistant media", assistantMedia, false),
                Arguments.of("assistant media", assistantMedia, true));
    }

    @Test
    void ragContentAddedEarlierInTheChainIsInspected() {
        RecordingModel model = new RecordingModel();
        CallAdvisor rag =
                new CallAdvisor() {
                    public ChatClientResponse adviseCall(
                            ChatClientRequest request, CallAdvisorChain chain) {
                        return chain.nextCall(
                                request.mutate()
                                        .prompt(
                                                new Prompt(
                                                        List.of(
                                                                new SystemMessage(
                                                                        "retrieved attack"),
                                                                new UserMessage("hello"))))
                                        .build());
                    }

                    public int getOrder() {
                        return 100;
                    }

                    public String getName() {
                        return "fixture-rag";
                    }
                };
        assertThatThrownBy(
                        () -> client(model).prompt().user("hello").advisors(rag).call().content())
                .isInstanceOf(InspectionBlockedException.class);
        assertThat(model.calls).hasValue(0);
    }

    @Test
    void rejectsFormattingAppendedByTheTerminalModelAdvisorAfterInspection() {
        RecordingModel model = new RecordingModel();
        ChatClient client = client(model);
        String key = ChatClientAttributes.OUTPUT_FORMAT.getKey();
        assertThatThrownBy(
                        () ->
                                client.prompt()
                                        .user("hello")
                                        .advisors(a -> a.param(key, "attack"))
                                        .call()
                                        .content())
                .hasMessageContaining("UNSUPPORTED_CONTENT");
        StepVerifier.create(
                        client.prompt().user("hello").advisors(a -> a.param(key, "attack")).stream()
                                .content())
                .expectErrorMatches(error -> error.getMessage().contains("UNSUPPORTED_CONTENT"))
                .verify(Duration.ofSeconds(5));
        assertThat(model.calls).hasValue(0);
    }

    @Test
    void rejectsRequestAddedLateAdvisorsBeforeAnyModelCall() {
        RecordingModel model = new RecordingModel();
        CallAdvisor late =
                new CallAdvisor() {
                    public ChatClientResponse adviseCall(
                            ChatClientRequest request, CallAdvisorChain chain) {
                        return chain.nextCall(request);
                    }

                    public int getOrder() {
                        return Integer.MAX_VALUE;
                    }

                    public String getName() {
                        return "late";
                    }
                };
        assertThatThrownBy(
                        () -> client(model).prompt().user("hello").advisors(late).call().content())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal model advisor");
        assertThat(model.calls).hasValue(0);
    }

    @Test
    void allowsObservationWrappingTheInspectionAndRejectsDuplicateConfiguration() {
        RecordingModel model = new RecordingModel();
        AtomicInteger observations = new AtomicInteger();
        CallAdvisor observer =
                new CallAdvisor() {
                    public ChatClientResponse adviseCall(
                            ChatClientRequest request, CallAdvisorChain chain) {
                        ChatClientResponse result = chain.nextCall(request);
                        observations.incrementAndGet();
                        return result;
                    }

                    public int getOrder() {
                        return 0;
                    }

                    public String getName() {
                        return "observer";
                    }
                };
        InspectionChatClientConfigurer configurer = configurer();
        ChatClient.Builder builder = configurer.configure(ChatClient.builder(model)).defaultAdvisors(observer);
        assertThatThrownBy(() -> configurer.configure(builder))
                .isInstanceOf(IllegalStateException.class);
        assertThat(builder.build().prompt().user("hello").call().content()).isEqualTo("done");
        assertThat(observations).hasValue(1);
    }

    @Test
    void cancellationDuringInspectionNeverStartsBusinessModel() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ContentInspector blocking =
                new ContentInspector() {
                    public boolean requiresPrivacyProcessedContent() {
                        return false;
                    }

                    public InspectionResult inspect(InspectionRequest request) {
                        started.countDown();
                        try {
                            new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            interrupted.countDown();
                        }
                        InspectionRequest.checkInterrupted();
                        return InspectionResult.failed(InspectionFailureCode.TIMEOUT);
                    }
                };
        RecordingModel model = new RecordingModel();
        InspectionChatClientConfigurer config =
                new InspectionChatClientConfigurer(
                        new InspectionService(
                                List.of(blocking),
                                InspectionPolicy.blockFindings(),
                                InspectionFailurePolicy.FAIL_OPEN));
        Disposable subscription =
                config.configure(ChatClient.builder(model)).build().prompt().user("hello").stream()
                        .content()
                        .subscribe();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        subscription.dispose();
        assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(model.calls).hasValue(0);
    }
}
