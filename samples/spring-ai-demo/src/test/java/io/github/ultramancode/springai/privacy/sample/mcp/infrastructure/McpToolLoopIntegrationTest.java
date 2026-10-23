package io.github.ultramancode.springai.privacy.sample.mcp.infrastructure;

import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.sample.shared.security.authorization.DemoToolAuthorization;
import io.github.ultramancode.springai.privacy.sample.shared.security.model.DemoRole;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.CustomerLookupArguments;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.context-path=/guardrails"
)
@Timeout(20)
class McpToolLoopIntegrationTest {

    private static final String EMPLOYEE_ID = "EMP-1234";
    private static final String EMAIL = "test@example.com";
    private static final String PHONE = "010-1234-5678";
    private static final String CUSTOMER_ID = "CUST-123456";
    private static final String INPUT =
            "직원번호는 EMP-1234이고 이메일은 test@example.com, 전화번호는 010-1234-5678, 고객번호는 CUST-123456입니다.";
    private static final Pattern EMPLOYEE_ID_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID");
    private static final Pattern EMAIL_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS");
    private static final Pattern PHONE_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("PHONE_NUMBER");
    private static final Pattern CUSTOMER_ID_TOKEN_PATTERN =
            OpaquePiiTokenFormat.patternForEntityType("CUSTOMER_ID");
    private static final List<TokenField> TOKEN_FIELDS = List.of(
            new TokenField("employeeId", EMPLOYEE_ID_TOKEN_PATTERN),
            new TokenField("email", EMAIL_TOKEN_PATTERN),
            new TokenField("phone", PHONE_TOKEN_PATTERN),
            new TokenField("customerId", CUSTOMER_ID_TOKEN_PATTERN)
    );
    private static final Map<String, String> CRM_RECORDS = Map.of(
            CUSTOMER_ID,
            "CRM result: 직원번호는 EMP-1234 / 이메일은 test@example.com / 전화번호는 010-1234-5678"
                    + " / 고객번호는 CUST-123456"
    );

    @Autowired
    private GuardrailsChatClientFactory guardrailsChatClientFactory;

    @Autowired
    private DemoToolAuthorization toolAuthorization;

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private McpCustomerLookupTool customerLookupTool;

    @Autowired
    private McpDemoClient mcpDemoClient;

    @Test
    void guardrailsFactoryProtectsModelAndMcpToolBoundaries() {
        McpBoundaryModel model = new McpBoundaryModel(this.objectMapper);

        this.customerLookupTool.replaceCustomerRecords(CRM_RECORDS);
        int callsBefore = this.customerLookupTool.getCallState().totalCallCount();
        ToolCallbackProvider mcpTools = this.mcpDemoClient.getToolCallbackProvider();
        ChatClient chatClient = this.guardrailsChatClientFactory.builder(model)
                .defaultTools(mcpTools)
                .build();
        String finalResponse = this.toolAuthorization.executeAsRole(
                DemoRole.CUSTOMER_SUPPORT,
                () -> chatClient.prompt().user(INPUT).call().content()
        ).result();

        McpCustomerLookupTool.ToolCallState callState = this.customerLookupTool.getCallState();
        assertThat(callState.totalCallCount() - callsBefore).isEqualTo(1);
        assertThat(callState.lastLookupSucceeded()).isTrue();
        CustomerLookupArguments arguments = callState.requireLastReceivedArguments();
        assertThat(arguments.customerId()).isEqualTo(CUSTOMER_ID);
        assertThat(arguments.employeeId())
                .matches(OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID"));
        assertThat(arguments.email())
                .matches(OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS"));
        assertThat(arguments.phone())
                .matches(OpaquePiiTokenFormat.patternForEntityType("PHONE_NUMBER"));

        assertThat(model.calls()).isEqualTo(2);
        assertThat(model.protectedToolResult()).isNotNull();
        assertThat(model.protectedToolResult())
                .doesNotContain(EMPLOYEE_ID, EMAIL, PHONE, CUSTOMER_ID);
        TOKEN_FIELDS.forEach(field -> assertThat(field.pattern().matcher(model.protectedToolResult()).find())
                .as("protected MCP result contains a %s token", field.name())
                .isTrue());
        assertThat(finalResponse).doesNotContain(EMPLOYEE_ID, EMAIL, PHONE, CUSTOMER_ID);

        assertThat(this.privacyService.activeSessionCount()).isZero();
    }

    @Test
    void mcpHandlerRecordsTheLatestLookupResultAndArguments() {
        this.customerLookupTool.replaceCustomerRecords(CRM_RECORDS);
        McpSyncClient mcpClient = this.mcpDemoClient.connect();

        int callsBefore = this.customerLookupTool.getCallState().totalCallCount();
        int call = 0;
        for (String customerId : List.of(CUSTOMER_ID, "UNKNOWN-CUSTOMER")) {
            call++;
            Map<String, Object> arguments = Map.of(
                    "employeeId", "employee-token-" + call,
                    "email", "email-token-" + call,
                    "phone", "phone-token-" + call,
                    "customerId", customerId
            );
            McpSchema.CallToolResult result = mcpClient.callTool(McpSchema.CallToolRequest.builder("customerLookup")
                    .arguments(arguments)
                    .build());

            boolean lookupSucceeded = CUSTOMER_ID.equals(customerId);
            assertThat(result.isError()).isEqualTo(!lookupSucceeded);
            McpCustomerLookupTool.ToolCallState callState = this.customerLookupTool.getCallState();
            assertThat(callState.totalCallCount() - callsBefore).isEqualTo(call);
            assertThat(callState.lastLookupSucceeded()).isEqualTo(lookupSucceeded);
            assertThat(callState.requireLastReceivedArguments()).isEqualTo(new CustomerLookupArguments(
                    "employee-token-" + call,
                    "email-token-" + call,
                    "phone-token-" + call,
                    customerId
            ));
        }
    }

    @Test
    void mcpHandlerRejectsMissingOrNonStringArgumentsBeforeRecordingALookup() {
        List<Map<String, Object>> invalidArguments = List.of(
                Map.of("employeeId", "employee-token", "email", 42,
                        "phone", "phone-token", "customerId", CUSTOMER_ID),
                Map.of("employeeId", "employee-token", "email", "email-token",
                        "customerId", CUSTOMER_ID)
        );
        this.customerLookupTool.replaceCustomerRecords(CRM_RECORDS);
        McpSyncClient mcpClient = this.mcpDemoClient.connect();

        McpCustomerLookupTool.ToolCallState callStateBefore = this.customerLookupTool.getCallState();
        for (Map<String, Object> arguments : invalidArguments) {
            McpSchema.CallToolResult result = mcpClient.callTool(
                    McpSchema.CallToolRequest.builder("customerLookup").arguments(arguments).build());

            assertThat(result.isError()).isTrue();
            assertThat(this.customerLookupTool.getCallState()).isEqualTo(callStateBefore);
        }
    }

    private record TokenField(String name, Pattern pattern) {
    }

    private static final class McpBoundaryModel implements ChatModel {

        private final ObjectMapper objectMapper;
        private int calls;
        private String protectedToolResult;

        private McpBoundaryModel(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.calls++;
            if (this.calls == 1) {
                CustomerLookupArguments arguments = new CustomerLookupArguments(
                        requireToken(prompt, EMPLOYEE_ID_TOKEN_PATTERN),
                        requireToken(prompt, EMAIL_TOKEN_PATTERN),
                        requireToken(prompt, PHONE_TOKEN_PATTERN),
                        requireToken(prompt, CUSTOMER_ID_TOKEN_PATTERN)
                );
                String toolArguments = this.objectMapper.writeValueAsString(arguments);
                AssistantMessage toolCall = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(new AssistantMessage.ToolCall(
                                "mcp-call-1",
                                "function",
                                "customerLookup",
                                toolArguments
                        )))
                        .build();
                return createChatResponse(toolCall);
            }

            this.protectedToolResult = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("MCP tool response was not returned to the model"));
            return createChatResponse(new AssistantMessage("Local model received protected MCP result: "
                    + this.protectedToolResult));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private static String requireToken(Prompt prompt, Pattern pattern) {
            for (Message message : prompt.getInstructions()) {
                String text = message.getText();
                if (text == null) {
                    continue;
                }
                Matcher matcher = pattern.matcher(text);
                if (matcher.find()) {
                    return matcher.group();
                }
            }
            throw new IllegalStateException("Expected protected token was not sent to the model");
        }

        private static ChatResponse createChatResponse(AssistantMessage message) {
            return new ChatResponse(List.of(new Generation(message)));
        }

        private int calls() {
            return this.calls;
        }

        private String protectedToolResult() {
            return this.protectedToolResult;
        }
    }

}
