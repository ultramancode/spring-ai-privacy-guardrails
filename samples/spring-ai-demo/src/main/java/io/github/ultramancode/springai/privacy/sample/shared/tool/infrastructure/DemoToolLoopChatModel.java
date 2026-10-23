package io.github.ultramancode.springai.privacy.sample.shared.tool.infrastructure;

import io.github.ultramancode.springai.privacy.sample.shared.model.CustomerDemoData;
import io.github.ultramancode.springai.privacy.sample.shared.model.DemoLanguage;
import io.github.ultramancode.springai.privacy.sample.shared.tool.model.CustomerLookupArguments;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static io.github.ultramancode.springai.privacy.sample.shared.tool.support.DemoPiiChecks.CUSTOMER_ID_TOKEN_PATTERN;
import static io.github.ultramancode.springai.privacy.sample.shared.tool.support.DemoPiiChecks.EMAIL_TOKEN_PATTERN;
import static io.github.ultramancode.springai.privacy.sample.shared.tool.support.DemoPiiChecks.EMPLOYEE_ID_TOKEN_PATTERN;
import static io.github.ultramancode.springai.privacy.sample.shared.tool.support.DemoPiiChecks.PHONE_TOKEN_PATTERN;
import static io.github.ultramancode.springai.privacy.sample.shared.tool.support.DemoPiiChecks.countContainedValues;

public final class DemoToolLoopChatModel implements ChatModel {

    private static final List<String> RAW_VALUES = CustomerDemoData.DEFAULT.originalValues();
    private static final List<Pattern> EXPECTED_TOKEN_PATTERNS = List.of(
            EMPLOYEE_ID_TOKEN_PATTERN,
            EMAIL_TOKEN_PATTERN,
            PHONE_TOKEN_PATTERN,
            CUSTOMER_ID_TOKEN_PATTERN
    );

    private final ObjectMapper objectMapper;
    private final String resultSourceLabel;
    private final DemoLanguage language;
    private final ToolCallingManager toolCallingManager;
    private final Set<String> rawValuesSeenByModel = new LinkedHashSet<>();
    private List<String> exposedToolNames = List.of();
    private int calls;
    private boolean protectedToolResultSeenByModel;
    private int rawToolResultValueCountAtModel;
    private String protectedModelInput;
    private String issuedToolArguments;

    public DemoToolLoopChatModel(
            ObjectMapper objectMapper,
            String resultSourceLabel,
            DemoLanguage language,
            ToolCallingManager toolCallingManager
    ) {
        this.objectMapper = objectMapper;
        this.resultSourceLabel = resultSourceLabel;
        this.language = language;
        this.toolCallingManager = Objects.requireNonNull(toolCallingManager, "toolCallingManager must not be null");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        recordExposedToolNames(prompt);
        this.calls++;
        recordRawValues(prompt);

        if (this.calls == 1) {
            return createToolCallResponse(prompt);
        }
        return createFinalResponse(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.just(call(prompt));
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    public int calls() {
        return this.calls;
    }

    public List<String> exposedToolNames() {
        return this.exposedToolNames;
    }

    public String protectedModelInput() {
        return this.protectedModelInput;
    }

    public String issuedToolArguments() {
        return this.issuedToolArguments;
    }

    public int rawValueCount() {
        return this.rawValuesSeenByModel.size();
    }

    public boolean protectedToolResultSeenByModel() {
        return this.protectedToolResultSeenByModel;
    }

    public int rawToolResultValueCountAtModel() {
        return this.rawToolResultValueCountAtModel;
    }

    private void recordExposedToolNames(Prompt prompt) {
        if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) {
            throw new IllegalStateException("Expected tool-calling options");
        }
        this.exposedToolNames = this.toolCallingManager.resolveToolDefinitions(options)
                .stream()
                .map(ToolDefinition::name)
                .sorted()
                .toList();
    }

    private void recordRawValues(Prompt prompt) {
        for (Message message : prompt.getInstructions()) {
            recordRawValuesInText(message.getText());
            if (message instanceof AssistantMessage assistantMessage) {
                for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                    recordRawValuesInText(toolCall.arguments());
                }
            }
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                    recordRawValuesInText(toolResponse.responseData());
                }
            }
        }
    }

    private void recordRawValuesInText(String text) {
        if (text == null) {
            return;
        }
        for (String rawValue : RAW_VALUES) {
            if (text.contains(rawValue)) {
                this.rawValuesSeenByModel.add(rawValue);
            }
        }
    }

    private ChatResponse createToolCallResponse(Prompt prompt) {
        this.protectedModelInput = prompt.getInstructions().stream()
                .map(Message::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        String employeeIdToken = requireToken(prompt, EMPLOYEE_ID_TOKEN_PATTERN);
        String emailToken = requireToken(prompt, EMAIL_TOKEN_PATTERN);
        String phoneToken = requireToken(prompt, PHONE_TOKEN_PATTERN);
        String customerIdToken = requireToken(prompt, CUSTOMER_ID_TOKEN_PATTERN);
        CustomerLookupArguments arguments = new CustomerLookupArguments(
                employeeIdToken, emailToken, phoneToken, customerIdToken);
        this.issuedToolArguments = this.objectMapper.writeValueAsString(arguments);

        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                "demo-call-1", "function", "customerLookup", this.issuedToolArguments);
        AssistantMessage toolCallMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(toolCall))
                .build();
        return createChatResponse(toolCallMessage);
    }

    private ChatResponse createFinalResponse(Prompt prompt) {
        String toolResult = requireToolResponse(prompt);
        this.rawToolResultValueCountAtModel = countContainedValues(toolResult, RAW_VALUES);
        if (this.rawToolResultValueCountAtModel > 0) {
            this.protectedToolResultSeenByModel = false;
        }
        else {
            this.protectedToolResultSeenByModel = EXPECTED_TOKEN_PATTERNS.stream()
                    .allMatch(pattern -> pattern.matcher(toolResult).find());
        }

        String responseText = formatProtectedToolResult(toolResult);
        return createChatResponse(new AssistantMessage(responseText));
    }

    private String formatProtectedToolResult(String protectedResult) {
        if (this.language == DemoLanguage.KO) {
            return "로컬 모델이 보호된 %s 결과를 수신했습니다: %s"
                    .formatted(this.resultSourceLabel, protectedResult);
        }
        return "Local model received protected %s result: %s"
                .formatted(this.resultSourceLabel, protectedResult);
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

    private static String requireToolResponse(Prompt prompt) {
        for (Message message : prompt.getInstructions()) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                List<ToolResponseMessage.ToolResponse> responses = toolResponseMessage.getResponses();
                if (!responses.isEmpty()) {
                    return Objects.requireNonNull(responses.get(0).responseData());
                }
            }
        }
        throw new IllegalStateException("Tool response was not returned to the model");
    }

    private ChatResponse createChatResponse(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)));
    }
}
