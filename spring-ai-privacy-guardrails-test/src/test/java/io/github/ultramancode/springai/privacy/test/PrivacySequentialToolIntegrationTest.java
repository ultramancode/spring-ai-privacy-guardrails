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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.github.ultramancode.springai.privacy.test.PrivacyTestAssertions.assertThatPrivacy;
import static org.assertj.core.api.Assertions.assertThat;

class PrivacySequentialToolIntegrationTest {

    private static final String CUSTOMER_ID = "CUST-246810";
    private static final String EMAIL_ADDRESS = "winner@example.test";
    private static final Pattern CUSTOMER_ID_TOKEN =
            OpaquePiiTokenFormat.patternForEntityType("CUSTOMER_ID");
    private static final Pattern EMAIL_ADDRESS_TOKEN =
            OpaquePiiTokenFormat.patternForEntityType("EMAIL_ADDRESS");

    @Test
    void chatClientExecutesTwoScopedToolsAndRetokenizesStructuredResultBetweenModelCalls() {
        PrivacyService privacyService = privacyService();
        PrivacyTestProbe probe = PrivacyTestProbe.create(privacyService);
        PrivacyToolCallbackFactory toolCallbackFactory = new PrivacyToolCallbackFactory(
                privacyService,
                ToolDisclosurePolicy.byToolName(Map.of(
                        "crmLookup", Set.of("CUSTOMER_ID"),
                        "emailCustomer", Set.of("EMAIL_ADDRESS")
                ))
        );
        SequentialToolModel model = new SequentialToolModel();
        ToolCallback crmLookup = probe.wrapTool(crmLookup(), toolCallbackFactory);
        ToolCallback emailCustomer = probe.wrapTool(emailCustomer(), toolCallbackFactory);
        ChatClient chatClient = ChatClient.builder(probe.wrapModel(model))
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(privacyService),
                        new PrivacyInputAdvisor(privacyService),
                        new PrivacyToolContextAdvisor(privacyService),
                        new PrivacyToolCallValidationAdvisor(privacyService),
                        new PrivacyModelBoundaryAdvisor(privacyService)
                )
                .defaultTools(crmLookup, emailCustomer)
                .build();

        String response = chatClient.prompt()
                .user("Look up " + CUSTOMER_ID + " and email the primary contact")
                .call()
                .content();

        assertThat(response).isEqualTo("Workflow complete");
        assertThat(model.calls()).isEqualTo(3);
        assertThatPrivacy(probe)
                .hasModelRequestCount(3)
                .hasToolCallCount(2)
                .modelRequestsDoNotContainRawValues(CUSTOMER_ID, EMAIL_ADDRESS)
                .modelRequestsContainOpaqueToken("CUSTOMER_ID")
                .modelRequestsContainOpaqueToken("EMAIL_ADDRESS")
                .toolInputsContain("crmLookup", CUSTOMER_ID)
                .toolInputsDoNotContainRawValues("crmLookup", EMAIL_ADDRESS)
                .toolOutputsContain("crmLookup", CUSTOMER_ID, EMAIL_ADDRESS)
                .toolInputsContain("emailCustomer", EMAIL_ADDRESS)
                .toolInputsDoNotContainRawValues("emailCustomer", CUSTOMER_ID)
                .toolInputsContainOpaqueToken("emailCustomer", "CUSTOMER_ID")
                .hasNoActivePrivacySessions();

        assertThat(probe.toolCalls())
                .filteredOn(call -> call.toolName().equals("emailCustomer"))
                .singleElement()
                .satisfies(call -> {
                    Map<?, ?> input = new ObjectMapper().readValue(call.input(), Map.class);
                    assertThat(input.get("email")).isEqualTo(EMAIL_ADDRESS);
                    assertThat(input.get("customerId").toString()).matches(CUSTOMER_ID_TOKEN);
                });
    }

    private PrivacyService privacyService() {
        return new PrivacyService(
                List.of(new RegexPiiAnalyzer(List.of(
                        new RegexPiiRule("CUSTOMER_ID", "\\bCUST-\\d{6}\\b", 0.99, 0),
                        new RegexPiiRule(
                                "EMAIL_ADDRESS",
                                "\\bwinner@example\\.test\\b",
                                0.99,
                                0
                        )
                ))),
                PiiAnalysisOptions.defaults()
        );
    }

    private ToolCallback crmLookup() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("crmLookup")
                        .description("Finds the synthetic customer profile")
                        .inputSchema("""
                                {
                                  "type": "object",
                                  "properties": {
                                    "customerId": {"type": "string"}
                                  },
                                  "required": ["customerId"]
                                }
                                """)
                        .build();
            }

            @Override
            public String call(String input) {
                return """
                        {
                          "profile": {
                            "customerId": "%s",
                            "contacts": [
                              {"kind": "primary", "email": "%s"}
                            ]
                          },
                          "revision": 2
                        }
                        """.formatted(CUSTOMER_ID, EMAIL_ADDRESS);
            }
        };
    }

    private ToolCallback emailCustomer() {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("emailCustomer")
                        .description("Emails the synthetic primary contact")
                        .inputSchema("""
                                {
                                  "type": "object",
                                  "properties": {
                                    "email": {"type": "string"},
                                    "customerId": {"type": "string"}
                                  },
                                  "required": ["email", "customerId"]
                                }
                                """)
                        .build();
            }

            @Override
            public String call(String input) {
                return "{\"status\":\"queued\"}";
            }
        };
    }

    private static final class SequentialToolModel implements ChatModel {

        private final AtomicInteger calls = new AtomicInteger();
        private final ObjectMapper objectMapper = new ObjectMapper();

        @Override
        public ChatResponse call(Prompt prompt) {
            return switch (this.calls.incrementAndGet()) {
                case 1 -> requestCrmLookup(prompt);
                case 2 -> requestEmailDelivery(prompt);
                case 3 -> completeAfterDelivery(prompt);
                default -> throw new IllegalStateException("Unexpected model invocation");
            };
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        int calls() {
            return this.calls.get();
        }

        private ChatResponse requestCrmLookup(Prompt prompt) {
            String customerToken = findToken(prompt, CUSTOMER_ID_TOKEN);
            return toolCall(
                    "call-crm",
                    "crmLookup",
                    this.objectMapper.writeValueAsString(Map.of("customerId", customerToken))
            );
        }

        private ChatResponse requestEmailDelivery(Prompt prompt) {
            String protectedCrmResult = toolResponse(prompt, "crmLookup");
            Map<?, ?> result = this.objectMapper.readValue(protectedCrmResult, Map.class);
            Map<?, ?> profile = requiredMap(result.get("profile"), "profile");
            List<?> contacts = requiredList(profile.get("contacts"), "contacts");
            Map<?, ?> primaryContact = requiredMap(contacts.get(0), "primary contact");
            String customerToken = requiredString(profile.get("customerId"), "customerId");
            String emailToken = requiredString(primaryContact.get("email"), "email");

            assertOpaqueToken(customerToken, CUSTOMER_ID_TOKEN, "customerId");
            assertOpaqueToken(emailToken, EMAIL_ADDRESS_TOKEN, "email");
            if (!Integer.valueOf(2).equals(result.get("revision"))) {
                throw new IllegalStateException("Structured CRM revision was not preserved");
            }

            return toolCall(
                    "call-email",
                    "emailCustomer",
                    this.objectMapper.writeValueAsString(Map.of(
                            "email", emailToken,
                            "customerId", customerToken
                    ))
            );
        }

        private ChatResponse completeAfterDelivery(Prompt prompt) {
            Map<?, ?> delivery = this.objectMapper.readValue(toolResponse(prompt, "emailCustomer"), Map.class);
            if (!"queued".equals(delivery.get("status"))) {
                throw new IllegalStateException("Email delivery response was not preserved");
            }
            return response(new AssistantMessage("Workflow complete"));
        }

        private String findToken(Prompt prompt, Pattern pattern) {
            return prompt.getInstructions().stream()
                    .map(message -> message.getText())
                    .filter(Objects::nonNull)
                    .map(pattern::matcher)
                    .filter(Matcher::find)
                    .map(Matcher::group)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Expected protected token was not present"));
        }

        private String toolResponse(Prompt prompt, String toolName) {
            return prompt.getInstructions().stream()
                    .filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .filter(response -> toolName.equals(response.name()))
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Expected response from tool " + toolName
                    ));
        }

        private ChatResponse toolCall(String id, String toolName, String arguments) {
            AssistantMessage request = AssistantMessage.builder()
                    .content("")
                    .toolCalls(List.of(new AssistantMessage.ToolCall(
                            id,
                            "function",
                            toolName,
                            arguments
                    )))
                    .build();
            return response(request);
        }

        private ChatResponse response(AssistantMessage message) {
            return new ChatResponse(List.of(new Generation(message)));
        }

        private Map<?, ?> requiredMap(Object value, String field) {
            if (value instanceof Map<?, ?> map) {
                return map;
            }
            throw new IllegalStateException("Expected JSON object for " + field);
        }

        private List<?> requiredList(Object value, String field) {
            if (value instanceof List<?> list && !list.isEmpty()) {
                return list;
            }
            throw new IllegalStateException("Expected non-empty JSON array for " + field);
        }

        private String requiredString(Object value, String field) {
            if (value instanceof String string) {
                return string;
            }
            throw new IllegalStateException("Expected JSON string for " + field);
        }

        private void assertOpaqueToken(String value, Pattern pattern, String field) {
            if (!pattern.matcher(value).matches()) {
                throw new IllegalStateException("Expected opaque token for " + field);
            }
        }
    }
}
