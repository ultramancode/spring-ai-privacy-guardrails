package io.github.ultramancode.springai.privacy.sample.integration;

import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.sample.localtool.infrastructure.DemoCustomerLookupTool;
import io.github.ultramancode.springai.privacy.test.PrivacyTestProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static io.github.ultramancode.springai.privacy.test.PrivacyTestAssertions.assertThatPrivacy;
import static org.assertj.core.api.Assertions.assertThat;

@Tag("live-model")
@EnabledIfEnvironmentVariable(named = "OPENAI_COMPATIBLE_API_KEY", matches = "\\S+")
@SpringBootTest(
        classes = LiveModelTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.banner-mode=off",
                "spring.ai.model.chat=openai",
                "spring.ai.model.embedding=none",
                "spring.ai.model.embedding.text=none",
                "spring.ai.model.embedding.multimodal=none",
                "spring.ai.model.image=none",
                "spring.ai.model.audio.transcription=none",
                "spring.ai.model.audio.speech=none",
                "spring.ai.model.moderation=none",
                "spring.ai.openai.api-key=${OPENAI_COMPATIBLE_API_KEY}",
                "spring.ai.openai.base-url=${OPENAI_COMPATIBLE_BASE_URL}",
                "spring.ai.openai.chat.model=${OPENAI_COMPATIBLE_MODEL}",
                "spring.ai.openai.chat.temperature=0",
                "spring.ai.retry.max-attempts=1",
                "debug=false",
                "logging.level.root=OFF"
        }
)
class OpenAiCompatibleLiveIntegrationTest {

    private static final String EMPLOYEE_ID = "EMP-1234";
    private static final String EMAIL = "test@example.com";
    private static final String LOOKUP_REQUEST_EMAIL = "lookup-request@example.net";
    private static final String PHONE = "010-1234-5678";
    private static final String CUSTOMER_ID = "CUST-123456";
    private static final List<String> TOOL_LOOP_RAW_VALUES = List.of(
            EMPLOYEE_ID,
            LOOKUP_REQUEST_EMAIL,
            EMAIL,
            PHONE,
            CUSTOMER_ID
    );

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private GuardrailsChatClientFactory guardrailsChatClientFactory;

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void privacySessionIsClosedAfterEveryScenario() {
        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    void blockingCallProtectsTheActualModelBoundary() {
        long startedAt = System.nanoTime();
        try (PrivacyTestProbe privacyTestProbe = PrivacyTestProbe.create(this.privacyService)) {
            ChatClient chatClient = protectedClient(privacyTestProbe);
            String response = chatClient.prompt()
                    .system("Reply briefly without repeating identifiers.")
                    .user("Acknowledge employee EMP-9001 and email live-e2e@example.test.")
                    .call()
                    .content();

            assertThat(response)
                    .isNotBlank()
                    .doesNotContain("EMP-9001", "live-e2e@example.test");
            assertThatPrivacy(privacyTestProbe)
                .hasModelRequestCount(1)
                .hasToolCallCount(0)
                .modelRequestsDoNotContainRawValues("EMP-9001", "live-e2e@example.test")
                .modelRequestsContainOpaqueToken("EMPLOYEE_ID")
                .modelRequestsContainOpaqueToken("EMAIL_ADDRESS")
                .hasNoActivePrivacySessions();
            report("blocking", privacyTestProbe, startedAt);
        }
    }

    @Test
    void streamingCallProtectsTheActualModelBoundary() {
        long startedAt = System.nanoTime();
        try (PrivacyTestProbe privacyTestProbe = PrivacyTestProbe.create(this.privacyService)) {
            ChatClient chatClient = protectedClient(privacyTestProbe);
            String response = chatClient.prompt()
                    .system("Reply briefly without repeating identifiers.")
                    .user("Acknowledge email stream-e2e@example.test.")
                    .stream()
                    .content()
                    .collectList()
                    .map(parts -> String.join("", parts))
                    .block(Duration.ofSeconds(90));

            assertThat(response)
                    .isNotBlank()
                    .doesNotContain("stream-e2e@example.test");
            assertThatPrivacy(privacyTestProbe)
                .hasModelRequestCount(1)
                .hasToolCallCount(0)
                .modelRequestsDoNotContainRawValues("stream-e2e@example.test")
                .modelRequestsContainOpaqueToken("EMAIL_ADDRESS")
                .hasNoActivePrivacySessions();
            report("streaming", privacyTestProbe, startedAt);
        }
    }

    @Test
    void toolLoopScopesDisclosureAndRetokenizesTheActualToolResult() {
        long startedAt = System.nanoTime();
        try (PrivacyTestProbe privacyTestProbe = PrivacyTestProbe.create(this.privacyService)) {
            DemoCustomerLookupTool delegate = new DemoCustomerLookupTool(this.objectMapper);
            ToolCallback recordingTool = privacyTestProbe.wrapTool(delegate);
            ChatClient chatClient = protectedClient(privacyTestProbe, recordingTool);

            String response = chatClient.prompt()
                    .system("""
                            This is a deterministic tool integration test.
                            Call customerLookup exactly once before answering.
                            Copy all four values from the user into the matching tool fields exactly,
                            even when a value looks like an opaque placeholder.
                            After receiving the tool result, answer only: lookup complete
                            """)
                    .user("""
                            Call customerLookup with employeeId EMP-1234, email %s,
                            phone 010-1234-5678, and customerId CUST-123456.
                            """.formatted(LOOKUP_REQUEST_EMAIL))
                    .call()
                    .content();

            assertThat(response)
                    .isNotBlank()
                    .doesNotContain(TOOL_LOOP_RAW_VALUES.toArray(String[]::new));
            assertThatPrivacy(privacyTestProbe)
                .hasModelRequestCount(2)
                .hasToolCallCount(1)
                .modelRequestsDoNotContainRawValues(TOOL_LOOP_RAW_VALUES.toArray(String[]::new))
                .modelRequestsContainOpaqueToken("EMPLOYEE_ID")
                .modelRequestsContainOpaqueToken("EMAIL_ADDRESS")
                .modelRequestsContainOpaqueToken("PHONE_NUMBER")
                .modelRequestsContainOpaqueToken("CUSTOMER_ID")
                .modelRequestHasDistinctOpaqueTokenCount(0, "EMAIL_ADDRESS", 1)
                .modelRequestHasDistinctOpaqueTokenCount(1, "EMAIL_ADDRESS", 2)
                .toolInputsContain("customerLookup", CUSTOMER_ID)
                .toolInputsDoNotContainRawValues(
                    "customerLookup",
                    EMPLOYEE_ID,
                    LOOKUP_REQUEST_EMAIL,
                    PHONE
                )
                .toolInputsContainOpaqueToken("customerLookup", "EMPLOYEE_ID")
                .toolInputsContainOpaqueToken("customerLookup", "EMAIL_ADDRESS")
                .toolInputsContainOpaqueToken("customerLookup", "PHONE_NUMBER")
                .toolOutputsContain(
                    "customerLookup",
                    EMPLOYEE_ID,
                    EMAIL,
                    PHONE,
                    CUSTOMER_ID
                )
                .hasNoActivePrivacySessions();
            assertThat(delegate.receivedOnlyAllowedOriginals()).isTrue();
            assertThat(delegate.lookupSucceeded()).isTrue();
            report("tool-loop", privacyTestProbe, startedAt);
        }
    }

    @Test
    void returnDirectToolResultReceivesOutputProtection() {
        long startedAt = System.nanoTime();
        try (PrivacyTestProbe privacyTestProbe = PrivacyTestProbe.create(this.privacyService)) {
            ToolCallback recordingTool = privacyTestProbe.wrapTool(returnDirectTool());
            ChatClient chatClient = protectedClient(privacyTestProbe, recordingTool);

            String response = chatClient.prompt()
                    .system("""
                            This is a deterministic tool integration test.
                            Call directReceipt exactly once and do not answer directly.
                            Copy both values from the user into the matching tool fields exactly,
                            even when a value looks like an opaque placeholder.
                            """)
                    .user("Create a direct receipt for employee EMP-1234 and email test@example.com.")
                    .call()
                    .content();

            assertThat(response)
                    .isNotBlank()
                    .doesNotContain(EMPLOYEE_ID, EMAIL)
                    .contains("[PII_EMPLOYEE_ID_", "[PII_EMAIL_ADDRESS_");
            assertThatPrivacy(privacyTestProbe)
                .hasModelRequestCount(1)
                .hasToolCallCount(1)
                .modelRequestsDoNotContainRawValues(EMPLOYEE_ID, EMAIL)
                .modelRequestsContainOpaqueToken("EMPLOYEE_ID")
                .modelRequestsContainOpaqueToken("EMAIL_ADDRESS")
                .toolInputsDoNotContainRawValues("directReceipt", EMPLOYEE_ID, EMAIL)
                .toolOutputsContain("directReceipt", EMPLOYEE_ID, EMAIL)
                .hasNoActivePrivacySessions();
            report("return-direct", privacyTestProbe, startedAt);
        }
    }

    private ChatClient protectedClient(PrivacyTestProbe privacyTestProbe, ToolCallback... tools) {
        ChatClient.Builder chatClientBuilder = this.guardrailsChatClientFactory
            .builder(privacyTestProbe.wrapModel(this.chatModel));
        if (tools.length > 0) {
            chatClientBuilder.defaultTools((Object[]) tools);
        }
        return chatClientBuilder.build();
    }

    private ToolCallback returnDirectTool() {
        return FunctionToolCallback.builder(
                        "directReceipt",
                        (DirectReceiptRequest ignored) ->
                                "Direct receipt for employee " + EMPLOYEE_ID + " at " + EMAIL
                )
                .description("Returns a synthetic receipt directly to the application")
                .inputType(DirectReceiptRequest.class)
                .toolMetadata(ToolMetadata.builder().returnDirect(true).build())
                .build();
    }

    private void report(String scenario, PrivacyTestProbe privacyTestProbe, long startedAt) {
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        System.out.printf(
            "LIVE_LLM_RESULT adapter=openai-compatible scenario=%s status=PASS "
                + "modelRequests=%d toolCalls=%d "
                + "activeSessions=%d elapsedMs=%d%n",
            scenario,
            privacyTestProbe.modelRequests().size(),
            privacyTestProbe.toolCalls().size(),
            this.privacyService.activeSessionCount(),
            elapsedMillis
        );
    }

    private record DirectReceiptRequest(String employeeId, String email) {
    }
}
