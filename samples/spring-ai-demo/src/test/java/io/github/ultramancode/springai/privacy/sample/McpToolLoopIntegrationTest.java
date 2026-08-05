package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.modelcontextprotocol.client.McpSyncClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
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
    private PrivacyChatClientConfigurer privacyConfigurer;

    @Autowired
    private PrivacyToolCallbackFactory toolCallbackFactory;

    @Autowired
    private PrivacyService privacyService;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    private Path tempDirectory;

    @Test
    void mcpProviderRestoresOnlyAllowedArgumentsAcrossAnActualStreamableHttpRoundTrip() {
        McpBoundaryModel model = new McpBoundaryModel(this.objectMapper);

        try (LocalMcpCrmServer server = LocalMcpCrmServer.start(this.tempDirectory, CRM_RECORDS);
             McpSyncClient mcpClient = server.connect()) {
            mcpClient.initialize();

            ToolCallbackProvider mcpTools = SyncMcpToolCallbackProvider.builder()
                    .mcpClients(mcpClient)
                    .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
                    .build();
            ToolCallbackProvider protectedMcpTools = this.toolCallbackFactory.wrapProvider(mcpTools);
            ChatClient.Builder builder = ChatClient.builder(model).defaultTools(protectedMcpTools);
            this.privacyConfigurer.configure(builder);

            String finalResponse = builder.build().prompt().user(INPUT).call().content();

            assertThat(server.calls()).isEqualTo(1);
            assertThat(server.lookupSucceeded()).isTrue();
            assertThat(server.receivedArgument("customerId")).isEqualTo(CUSTOMER_ID);
            assertThat(server.receivedArgument("employeeId"))
                    .matches(OpaquePiiTokenFormat.patternForEntityType("EMPLOYEE_ID"));
            assertThat(server.receivedArgument("email"))
                    .matches(OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS"));
            assertThat(server.receivedArgument("phone"))
                    .matches(OpaquePiiTokenFormat.patternForEntityType("PHONE_NUMBER"));

            assertThat(model.calls()).isEqualTo(2);
            assertThat(model.protectedToolResult()).isNotNull();
            assertThat(model.protectedToolResult())
                    .doesNotContain(EMPLOYEE_ID, EMAIL, PHONE, CUSTOMER_ID);
            TOKEN_FIELDS.forEach(field -> assertThat(field.pattern().matcher(model.protectedToolResult()).find())
                    .as("protected MCP result contains a %s token", field.name())
                    .isTrue());
            assertThat(finalResponse).doesNotContain(EMPLOYEE_ID, EMAIL, PHONE, CUSTOMER_ID);
        }

        assertThat(this.privacyService.activeSessionCount()).isZero();
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
                CrmLookupArguments arguments = new CrmLookupArguments(
                        findToken(prompt, EMPLOYEE_ID_TOKEN_PATTERN),
                        findToken(prompt, EMAIL_TOKEN_PATTERN),
                        findToken(prompt, PHONE_TOKEN_PATTERN),
                        findToken(prompt, CUSTOMER_ID_TOKEN_PATTERN)
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
                return response(toolCall);
            }

            this.protectedToolResult = prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("MCP tool response was not returned to the model"));
            return response(new AssistantMessage("Local model received protected MCP result: "
                    + this.protectedToolResult));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        private static String findToken(Prompt prompt, Pattern pattern) {
            return prompt.getInstructions().stream()
                    .map(Message::getText)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"))
                    .lines()
                    .map(pattern::matcher)
                    .filter(Matcher::find)
                    .map(Matcher::group)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Expected protected token was not sent to the model"));
        }

        private static ChatResponse response(AssistantMessage message) {
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
