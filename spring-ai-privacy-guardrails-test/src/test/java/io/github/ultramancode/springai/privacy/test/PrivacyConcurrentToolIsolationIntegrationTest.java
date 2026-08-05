package io.github.ultramancode.springai.privacy.test;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.RegexPiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.RegexPiiRule;
import io.github.ultramancode.springai.privacy.springai.PrivacyInputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyLifecycleAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyModelBoundaryAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolContextAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallValidationAdvisor;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.ultramancode.springai.privacy.test.PrivacyTestAssertions.assertThatPrivacy;
import static org.assertj.core.api.Assertions.assertThat;

class PrivacyConcurrentToolIsolationIntegrationTest {

    private static final String LEFT_ID = "CUST-100001";
    private static final String RIGHT_ID = "CUST-200002";
    private static final Pattern CUSTOMER_TOKEN =
            OpaquePiiTokenFormat.patternForEntityType("CUSTOMER_ID");
    private static final Pattern REQUEST_LABEL = Pattern.compile("request=(left|right)");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Test
    void sharedChatClientKeepsConcurrentToolLoopsInSeparatePrivacySessions() throws Exception {
        PrivacyService privacyService = privacyService();
        PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService);
        AtomicInteger activeSessionsAtToolBarrier = new AtomicInteger();
        CyclicBarrier toolBarrier = new CyclicBarrier(
                2,
                () -> activeSessionsAtToolBarrier.set(privacyService.activeSessionCount())
        );
        Map<String, String> rawIdByLabel = new ConcurrentHashMap<>();
        ConcurrentToolModel model = new ConcurrentToolModel();
        PrivacyToolCallbackFactory toolCallbackFactory = new PrivacyToolCallbackFactory(
                privacyService,
                ToolDisclosurePolicy.byToolName(Map.of("customerLookup", Set.of("CUSTOMER_ID")))
        );
        ToolCallback protectedTool = probe.wrapTool(
                customerLookup(toolBarrier, rawIdByLabel),
                toolCallbackFactory
        );
        ChatClient chatClient = ChatClient.builder(probe.wrapModel(model))
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(privacyService),
                        new PrivacyInputAdvisor(privacyService),
                        new PrivacyToolContextAdvisor(privacyService),
                        new PrivacyToolCallValidationAdvisor(privacyService),
                        new PrivacyModelBoundaryAdvisor(privacyService)
                )
                .defaultTools(protectedTool)
                .build();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> left = executor.submit(() -> invoke(chatClient, start, "left", LEFT_ID));
            Future<String> right = executor.submit(() -> invoke(chatClient, start, "right", RIGHT_ID));
            start.countDown();

            assertThat(left.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo("complete-left");
            assertThat(right.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isEqualTo("complete-right");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }

        assertThat(activeSessionsAtToolBarrier).hasValue(2);
        assertThat(rawIdByLabel).containsExactlyInAnyOrderEntriesOf(Map.of(
                "left", LEFT_ID,
                "right", RIGHT_ID
        ));
        assertThat(model.tokenByLabel()).containsKeys("left", "right");
        assertThat(model.tokenByLabel().get("left"))
                .matches(CUSTOMER_TOKEN)
                .isNotEqualTo(model.tokenByLabel().get("right"));
        assertThat(model.tokenByLabel().get("right")).matches(CUSTOMER_TOKEN);
        assertThat(probe.modelRequests())
                .hasSize(4)
                .flatExtracting(ModelRequestSnapshot::modelVisibleContent)
                .noneMatch(content -> content.contains(LEFT_ID) || content.contains(RIGHT_ID));
        assertThat(probe.toolCalls()).hasSize(2).allSatisfy(call -> {
            Map<?, ?> input = new ObjectMapper().readValue(call.input(), Map.class);
            String label = input.get("request").toString();
            String expected = label.equals("left") ? LEFT_ID : RIGHT_ID;
            String foreign = label.equals("left") ? RIGHT_ID : LEFT_ID;
            assertThat(input.get("customerId")).isEqualTo(expected);
            assertThat(call.input()).doesNotContain(foreign);
        });
        assertThatPrivacy(probe)
                .hasModelRequestCount(4)
                .hasToolCallCount(2)
                .modelRequestsDoNotContainRawValues(LEFT_ID, RIGHT_ID)
                .hasNoActivePrivacySessions();
        assertThat(privacyService.activeSessionCount()).isZero();
    }

    private PrivacyService privacyService() {
        return new PrivacyService(
                List.of(new RegexPiiAnalyzer(List.of(
                        new RegexPiiRule("CUSTOMER_ID", "\\bCUST-\\d{6}\\b", 0.99, 0)
                ))),
                PiiAnalysisOptions.defaults()
        );
    }

    private ToolCallback customerLookup(
            CyclicBarrier barrier,
            Map<String, String> rawIdByLabel
    ) {
        ObjectMapper objectMapper = new ObjectMapper();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("customerLookup")
                        .description("Returns the synthetic customer identifier")
                        .inputSchema("""
                                {
                                  "type": "object",
                                  "properties": {
                                    "request": {"type": "string"},
                                    "customerId": {"type": "string"}
                                  },
                                  "required": ["request", "customerId"]
                                }
                                """)
                        .build();
            }

            @Override
            public String call(String input) {
                Map<?, ?> parsed = objectMapper.readValue(input, Map.class);
                String label = requiredString(parsed.get("request"), "request");
                String customerId = requiredString(parsed.get("customerId"), "customerId");
                rawIdByLabel.put(label, customerId);
                await(barrier);
                return objectMapper.writeValueAsString(Map.of(
                        "request", label,
                        "customerId", customerId
                ));
            }
        };
    }

    private String invoke(
            ChatClient chatClient,
            CountDownLatch start,
            String label,
            String customerId
    ) {
        await(start);
        return chatClient.prompt()
                .user("request=" + label + " find " + customerId)
                .call()
                .content();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Concurrent request start timed out");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent request start was interrupted");
        }
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent tool execution was interrupted");
        } catch (BrokenBarrierException | TimeoutException failure) {
            throw new IllegalStateException("Concurrent tool execution did not overlap");
        }
    }

    private static String requiredString(Object value, String field) {
        if (value instanceof String string) {
            return string;
        }
        throw new IllegalStateException("Expected string field " + field);
    }

    private static final class ConcurrentToolModel implements ChatModel {

        private final ObjectMapper objectMapper = new ObjectMapper();
        private final Map<String, String> tokenByLabel = new ConcurrentHashMap<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            String label = requestLabel(prompt);
            return hasToolResponse(prompt)
                    ? complete(prompt, label)
                    : requestLookup(prompt, label);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        Map<String, String> tokenByLabel() {
            return Map.copyOf(this.tokenByLabel);
        }

        private ChatResponse requestLookup(Prompt prompt, String label) {
            String customerToken = findToken(prompt);
            this.tokenByLabel.put(label, customerToken);
            return toolCall(
                    "call-" + label,
                    this.objectMapper.writeValueAsString(Map.of(
                            "request", label,
                            "customerId", customerToken
                    ))
            );
        }

        private ChatResponse complete(Prompt prompt, String label) {
            String expectedToken = this.tokenByLabel.get(label);
            Map<?, ?> result = this.objectMapper.readValue(toolResponse(prompt), Map.class);
            String responseLabel = requiredString(result.get("request"), "request");
            String resultToken = requiredString(result.get("customerId"), "customerId");
            if (!label.equals(responseLabel) || !resultToken.equals(expectedToken)) {
                throw new IllegalStateException("Concurrent privacy session identity crossed requests");
            }
            String foreignLabel = label.equals("left") ? "right" : "left";
            String foreignToken = this.tokenByLabel.get(foreignLabel);
            if (foreignToken == null || promptContains(prompt, foreignToken)) {
                throw new IllegalStateException("Concurrent model prompt contained a foreign opaque token");
            }
            return response(new AssistantMessage("complete-" + label));
        }

        private boolean hasToolResponse(Prompt prompt) {
            return prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
        }

        private String requestLabel(Prompt prompt) {
            return prompt.getInstructions().stream()
                    .map(message -> message.getText())
                    .filter(Objects::nonNull)
                    .map(REQUEST_LABEL::matcher)
                    .filter(Matcher::find)
                    .map(matcher -> matcher.group(1))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Expected synthetic request label"));
        }

        private String findToken(Prompt prompt) {
            return prompt.getInstructions().stream()
                    .map(message -> message.getText())
                    .filter(Objects::nonNull)
                    .map(CUSTOMER_TOKEN::matcher)
                    .filter(Matcher::find)
                    .map(Matcher::group)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Expected protected customer token"));
        }

        private String toolResponse(Prompt prompt) {
            return prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .filter(response -> "customerLookup".equals(response.name()))
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Expected customerLookup response"));
        }

        private boolean promptContains(Prompt prompt, String value) {
            for (Message message : prompt.getInstructions()) {
                if (message.getText() != null && message.getText().contains(value)) {
                    return true;
                }
                if (message instanceof AssistantMessage assistantMessage
                        && assistantMessage.getToolCalls().stream()
                        .anyMatch(toolCall -> toolCall.arguments().contains(value))) {
                    return true;
                }
                if (message instanceof ToolResponseMessage toolResponseMessage
                        && toolResponseMessage.getResponses().stream()
                        .anyMatch(response -> response.responseData().contains(value))) {
                    return true;
                }
            }
            return false;
        }

        private ChatResponse toolCall(String id, String arguments) {
            AssistantMessage request = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            id,
                            "function",
                            "customerLookup",
                            arguments
                    )))
                    .build();
            return response(request);
        }

        private ChatResponse response(AssistantMessage message) {
            return new ChatResponse(List.of(new Generation(message)));
        }
    }
}
