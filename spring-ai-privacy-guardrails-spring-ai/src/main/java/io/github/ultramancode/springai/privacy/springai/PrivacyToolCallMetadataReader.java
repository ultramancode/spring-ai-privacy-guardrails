package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads the response-metadata tool-call channel consumed by Spring AI's stream aggregator. */
final class PrivacyToolCallMetadataReader {

    static final String TOOL_CALLS_KEY = "toolCalls";

    private PrivacyToolCallMetadataReader() {
    }

    static List<AssistantMessage.ToolCall> read(ChatResponse response, PrivacyPhase phase) {
        Objects.requireNonNull(response, "response must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        if (!response.getMetadata().containsKey(TOOL_CALLS_KEY)) {
            return List.of();
        }
        Object metadataValue = response.getMetadata().get(TOOL_CALLS_KEY);
        if (!(metadataValue instanceof List<?> toolCallValues)) {
            throw malformed(phase);
        }
        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>(toolCallValues.size());
        for (Object candidate : toolCallValues) {
            if (!(candidate instanceof AssistantMessage.ToolCall toolCall)) {
                throw malformed(phase);
            }
            toolCalls.add(toolCall);
        }
        return List.copyOf(toolCalls);
    }

    private static PrivacyGuardrailException malformed(PrivacyPhase phase) {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                phase,
                "Streaming response tool-call metadata is invalid"
        );
    }
}
