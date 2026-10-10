package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only extraction of supported runtime payloads. Tool definitions and tool-call identifiers are excluded. */
final class InspectionTextExtractor {
    static final List<String> TEXT_METADATA_KEYS = List.of("reasoningContent", "thinking");
    private final InspectionLimits limits;
    private final ContentSegment.PrivacyProcessingStatus status;
    private final String idPrefix;
    private final List<ContentSegment> segments = new ArrayList<>();
    private int characters;

    InspectionTextExtractor(InspectionLimits limits, ContentSegment.PrivacyProcessingStatus status,
            String idPrefix) {
        this.limits = limits;
        this.status = status;
        this.idPrefix = idPrefix;
    }

    void message(Message message) {
        InspectionRequest.checkInterrupted();
        if (message.getClass() == ToolResponseMessage.class) {
            for (ToolResponseMessage.ToolResponse response : ((ToolResponseMessage) message).getResponses()) {
                payload(response.responseData(), ContentSegment.Role.TOOL);
            }
        } else if (message.getClass() == UserMessage.class) {
            if (!((UserMessage) message).getMedia().isEmpty()) {
                throw unsupported();
            }
            payload(message.getText(), ContentSegment.Role.USER);
        } else if (message.getClass() == SystemMessage.class) {
            payload(message.getText(), ContentSegment.Role.SYSTEM);
        } else if (message instanceof AssistantMessage assistant) {
            requireSupportedAssistant(assistant);
            payload(assistant.getText(), ContentSegment.Role.ASSISTANT);
            String reasoning = reasoning(assistant);
            if (reasoning != null) {
                payload(reasoning, ContentSegment.Role.ASSISTANT);
            }
            metadata(assistant.getMetadata());
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                payload(call.arguments(), ContentSegment.Role.ASSISTANT);
            }
        } else {
            throw unsupported();
        }
    }

    static void requireSupportedAssistant(AssistantMessage message) {
        if ((message.getClass() != AssistantMessage.class && !isDeepSeek(message))
                || !message.getMedia().isEmpty()) {
            throw unsupported();
        }
    }

    private static boolean isDeepSeek(AssistantMessage message) {
        return message.getClass().getName().equals("org.springframework.ai.deepseek.DeepSeekAssistantMessage")
                && InspectionDeepSeekSupport.isExactType(message);
    }

    static String reasoning(AssistantMessage message) {
        return isDeepSeek(message) ? InspectionDeepSeekSupport.reasoning(message) : null;
    }

    void metadata(Map<String, Object> metadata) {
        for (String key : TEXT_METADATA_KEYS) {
            if (metadata.get(key) instanceof String text) {
                payload(text, ContentSegment.Role.ASSISTANT);
            }
        }
    }

    /** Input inspectors receive each complete payload, including JSON keys, syntax and escapes. */
    void payload(String text, ContentSegment.Role role) {
        addSegment(text, role, status);
    }

    /** Output inspectors receive each complete body, including JSON keys, syntax and escapes. */
    void outputPayload(String text, ContentSegment.PrivacyProcessingStatus outputStatus) {
        addSegment(text, ContentSegment.Role.ASSISTANT, outputStatus);
    }

    private void addSegment(String text, ContentSegment.Role role,
            ContentSegment.PrivacyProcessingStatus privacyStatus) {
        InspectionRequest.checkInterrupted();
        if (segments.size() >= limits.maxSegments()) {
            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
        }
        String content = text == null ? "" : text;
        if (content.length() > limits.maxCharacters() - characters) {
            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
        }
        characters += content.length();
        segments.add(new ContentSegment(idPrefix + segments.size(), role, privacyStatus, content));
    }

    List<ContentSegment> segments() {
        if (segments.isEmpty()) {
            payload("", ContentSegment.Role.UNKNOWN);
        }
        return List.copyOf(segments);
    }

    static InspectionException unsupported() {
        return new InspectionException(InspectionFailureCode.UNSUPPORTED_CONTENT);
    }
}
