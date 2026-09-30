package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.Generation;

import java.util.Objects;
import java.util.Set;

/**
 * Validates tool control structure and registered tool names before Spring AI executes calls.
 */
final class PrivacyToolControlValidator {

    private static final String UNKNOWN_TOOL_MESSAGE =
            "Model requested a tool outside the registered privacy boundary";

    void validateHistoryToolControlStructure(ChatClientRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        for (Message message : request.prompt().getInstructions()) {
            if (message instanceof AssistantMessage assistantMessage) {
                for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
                    requireToolCall(toolCall);
                }
            }
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    requireToolResponse(response);
                }
            }
        }
    }

    void validateResponseToolCalls(
            ChatClientResponse response,
            Set<String> registeredToolNames
    ) {
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(registeredToolNames, "registeredToolNames must not be null");
        if (response.chatResponse() == null) {
            return;
        }
        for (Generation generation : response.chatResponse().getResults()) {
            AssistantMessage message = generation.getOutput();
            if (message != null) {
                validateAssistantToolCalls(message, registeredToolNames);
            }
        }
        for (AssistantMessage.ToolCall toolCall : PrivacyToolCallMetadataReader.read(
                response.chatResponse(),
                PrivacyPhase.TOOL_INPUT
        )) {
            validateToolCall(toolCall, registeredToolNames);
        }
    }

    void validateAssistantToolCalls(
            AssistantMessage message,
            Set<String> registeredToolNames
    ) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(registeredToolNames, "registeredToolNames must not be null");
        for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
            validateToolCall(toolCall, registeredToolNames);
        }
    }

    void validateToolCallStructure(AssistantMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
            requireToolCall(toolCall);
        }
    }

    private void validateToolCall(
            AssistantMessage.ToolCall toolCall,
            Set<String> registeredToolNames
    ) {
        requireToolCall(toolCall);
        requireRegisteredToolName(toolCall, registeredToolNames);
    }

    private void requireRegisteredToolName(
            AssistantMessage.ToolCall toolCall,
            Set<String> registeredToolNames
    ) {
        boolean missingName = toolCall.name() == null || toolCall.name().isBlank();
        if (missingName || !registeredToolNames.contains(toolCall.name())) {
            throw unknownTool();
        }
    }

    private PrivacyGuardrailException unknownTool() {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                PrivacyPhase.TOOL_INPUT,
                UNKNOWN_TOOL_MESSAGE
        );
    }

    private void requireToolCall(AssistantMessage.ToolCall toolCall) {
        if (toolCall == null) {
            throw malformedControlField();
        }
    }

    private void requireToolResponse(ToolResponseMessage.ToolResponse response) {
        if (response == null) {
            throw malformedControlField();
        }
    }

    private PrivacyGuardrailException malformedControlField() {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                PrivacyPhase.TOOL_INPUT,
                "Tool control field is invalid"
        );
    }
}
