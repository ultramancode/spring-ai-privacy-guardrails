package io.github.ultramancode.springai.privacy.inspection.springai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;

/** Isolates the optional provider dependency. */
final class InspectionDeepSeekSupport {
    private InspectionDeepSeekSupport() {}

    static boolean isExactType(AssistantMessage message) {
        return message.getClass() == DeepSeekAssistantMessage.class;
    }

    static String reasoning(AssistantMessage message) {
        return ((DeepSeekAssistantMessage) message).getReasoningContent();
    }
}
