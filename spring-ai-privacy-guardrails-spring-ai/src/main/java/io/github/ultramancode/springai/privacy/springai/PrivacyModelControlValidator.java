package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Objects;
import java.util.Set;

/**
 * Validates model-visible tool control fields, tool definitions, output-format configuration,
 * and requested tool calls before Spring AI interprets or executes them.
 */
final class PrivacyModelControlValidator {

    private static final String UNKNOWN_TOOL_MESSAGE =
            "Model requested a tool outside the registered privacy boundary";

    private final PrivacyService privacyService;

    PrivacyModelControlValidator(PrivacyService privacyService) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
    }

    void validateHistoryToolControlFields(ChatClientRequest request) {
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

    void validateModelVisibleToolDefinitions(PrivacyContextHandle handle, ChatClientRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions toolOptions)
                || toolOptions.getToolCallbacks() == null) {
            return;
        }
        for (ToolCallback callback : toolOptions.getToolCallbacks()) {
            ToolDefinition definition = callback.getToolDefinition();
            rejectPiiPayload(handle, definition.description(), false, "Tool definition rejected by privacy guardrail");
            rejectPiiPayload(handle, definition.inputSchema(), true, "Tool definition rejected by privacy guardrail");
        }
    }

    void validateOutputFormatControlFields(PrivacyContextHandle handle, ChatClientRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        validateModelAugmentation(
                handle,
                request,
                ChatClientAttributes.OUTPUT_FORMAT.getKey(),
                false
        );
        validateModelAugmentation(
                handle,
                request,
                ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA.getKey(),
                true
        );
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
                validateAssistantMessage(message, registeredToolNames);
            }
        }
        for (AssistantMessage.ToolCall toolCall : PrivacyToolCallMetadataReader.read(
                response.chatResponse(),
                PrivacyPhase.TOOL_INPUT
        )) {
            validateToolCall(toolCall, registeredToolNames);
        }
    }

    AssistantMessage validateAssistantMessage(
            AssistantMessage message,
            Set<String> registeredToolNames
    ) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(registeredToolNames, "registeredToolNames must not be null");
        for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
            validateToolCall(toolCall, registeredToolNames);
        }
        return message;
    }

    AssistantMessage validateToolCallStructure(AssistantMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
            requireToolCall(toolCall);
        }
        return message;
    }

    private void rejectPiiPayload(
            PrivacyContextHandle handle,
            String value,
            boolean requireValidJson,
            String safeMessage
    ) {
        if (value != null && PrivacyJsonPayloadTransformer.containsPii(
                this.privacyService,
                handle,
                value,
                PrivacyPhase.TOKENIZATION,
                requireValidJson
        )) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    PrivacyPhase.TOKENIZATION,
                    safeMessage
            );
        }
    }

    private void validateModelAugmentation(
            PrivacyContextHandle handle,
            ChatClientRequest request,
            String contextKey,
            boolean requireValidJson
    ) {
        Object value = request.context().get(contextKey);
        if (value == null) {
            return;
        }
        if (!(value instanceof String text)) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    PrivacyPhase.TOKENIZATION,
                    "Terminal model augmentation is invalid"
            );
        }
        rejectPiiPayload(
                handle,
                text,
                requireValidJson,
                "Terminal model augmentation rejected by privacy guardrail"
        );
    }

    private void validateToolCall(
            AssistantMessage.ToolCall toolCall,
            Set<String> registeredToolNames
    ) {
        requireToolCall(toolCall);
        requireRegisteredName(toolCall, registeredToolNames);
    }

    private void requireRegisteredName(
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
