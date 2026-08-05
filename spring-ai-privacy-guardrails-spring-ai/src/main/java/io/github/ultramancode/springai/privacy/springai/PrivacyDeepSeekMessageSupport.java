package io.github.ultramancode.springai.privacy.springai;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;

import java.util.List;
import java.util.Map;

/** Isolates the optional Spring AI DeepSeek type from the provider-neutral boundary code. */
final class PrivacyDeepSeekMessageSupport {

    private PrivacyDeepSeekMessageSupport() {
    }

    static boolean isExactType(AssistantMessage message) {
        return message.getClass() == DeepSeekAssistantMessage.class;
    }

    static String reasoningContent(AssistantMessage message) {
        return ((DeepSeekAssistantMessage) message).getReasoningContent();
    }

    static AssistantMessage rebuild(
            AssistantMessage source,
            String content,
            Map<String, Object> metadata,
            List<AssistantMessage.ToolCall> toolCalls,
            List<Media> media,
            String reasoningContent
    ) {
        DeepSeekAssistantMessage deepSeekMessage = (DeepSeekAssistantMessage) source;
        return new DeepSeekAssistantMessage.Builder()
                .content(content)
                .reasoningContent(reasoningContent)
                .prefix(deepSeekMessage.getPrefix())
                .properties(metadata)
                .toolCalls(toolCalls)
                .media(media)
                .build();
    }
}
