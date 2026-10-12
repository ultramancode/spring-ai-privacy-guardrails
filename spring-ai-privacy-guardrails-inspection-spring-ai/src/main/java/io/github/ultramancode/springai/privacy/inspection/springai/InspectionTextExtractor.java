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

/** Collects supported runtime text into inspection segments without modifying source messages. */
final class InspectionTextExtractor {
    private static final List<String> REASONING_METADATA_KEYS = List.of("reasoningContent", "thinking");
    private final InspectionLimits limits;
    private final ContentSegment.PrivacyProcessingStatus defaultPrivacyStatus;
    private final String idPrefix;
    private final List<ContentSegment> segments = new ArrayList<>();
    private int totalCharacters;

    InspectionTextExtractor(InspectionLimits limits, ContentSegment.PrivacyProcessingStatus defaultPrivacyStatus,
            String idPrefix) {
        this.limits = limits;
        this.defaultPrivacyStatus = defaultPrivacyStatus;
        this.idPrefix = idPrefix;
    }

    void addMessage(Message message) {
        InspectionRequest.checkInterrupted();
        if (message.getClass() == ToolResponseMessage.class) {
            for (ToolResponseMessage.ToolResponse response : ((ToolResponseMessage) message).getResponses()) {
                addInputPayload(response.responseData(), ContentSegment.Role.TOOL);
            }
        } else if (message.getClass() == UserMessage.class) {
            if (!((UserMessage) message).getMedia().isEmpty()) {
                throw unsupported();
            }
            addInputPayload(message.getText(), ContentSegment.Role.USER);
        } else if (message.getClass() == SystemMessage.class) {
            addInputPayload(message.getText(), ContentSegment.Role.SYSTEM);
        } else if (message instanceof AssistantMessage assistant) {
            addAssistantMessage(assistant);
        } else {
            throw unsupported();
        }
    }

    private void addAssistantMessage(AssistantMessage assistant) {
        requireSupportedAssistant(assistant);
        // getText() returns the body only. Reasoning, metadata text and tool arguments are separate fields.
        addInputPayload(assistant.getText(), ContentSegment.Role.ASSISTANT);
        String reasoning = deepSeekReasoning(assistant);
        if (reasoning != null) {
            addInputPayload(reasoning, ContentSegment.Role.ASSISTANT);
        }
        addReasoningMetadataText(assistant.getMetadata());
        for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
            addInputPayload(call.arguments(), ContentSegment.Role.ASSISTANT);
        }
    }

    static void requireSupportedAssistant(AssistantMessage message) {
        boolean supportedType = message.getClass() == AssistantMessage.class || isDeepSeek(message);
        if (!supportedType || !message.getMedia().isEmpty()) {
            throw unsupported();
        }
    }

    private static boolean isDeepSeek(AssistantMessage message) {
        // Check the class name before accessing the optional DeepSeek dependency.
        return message.getClass().getName().equals("org.springframework.ai.deepseek.DeepSeekAssistantMessage")
                && InspectionDeepSeekSupport.isExactType(message);
    }

    private static String deepSeekReasoning(AssistantMessage message) {
        return isDeepSeek(message) ? InspectionDeepSeekSupport.reasoning(message) : null;
    }

    private void addReasoningMetadataText(Map<String, Object> metadata) {
        for (String key : REASONING_METADATA_KEYS) {
            if (metadata.get(key) instanceof String text) {
                addInputPayload(text, ContentSegment.Role.ASSISTANT);
            }
        }
    }

    /** Input inspectors receive each complete payload, including JSON keys, syntax and escapes. */
    private void addInputPayload(String text, ContentSegment.Role role) {
        addSegment(text, role, defaultPrivacyStatus);
    }

    /** Output inspectors receive each complete body, including JSON keys, syntax and escapes. */
    void addOutputPayload(String text, ContentSegment.PrivacyProcessingStatus outputStatus) {
        addSegment(text, ContentSegment.Role.ASSISTANT, outputStatus);
    }

    private void addSegment(String text, ContentSegment.Role role,
            ContentSegment.PrivacyProcessingStatus privacyStatus) {
        InspectionRequest.checkInterrupted();
        if (segments.size() >= limits.maxSegments()) {
            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
        }
        String content = text == null ? "" : text;
        if (content.length() > limits.maxCharacters() - totalCharacters) {
            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
        }
        totalCharacters += content.length();
        segments.add(new ContentSegment(idPrefix + segments.size(), role, privacyStatus, content));
    }

    List<ContentSegment> buildSegments() {
        if (segments.isEmpty()) {
            addSegment("", ContentSegment.Role.UNKNOWN, defaultPrivacyStatus);
        }
        return List.copyOf(segments);
    }

    static InspectionException unsupported() {
        return new InspectionException(InspectionFailureCode.UNSUPPORTED_CONTENT);
    }
}
