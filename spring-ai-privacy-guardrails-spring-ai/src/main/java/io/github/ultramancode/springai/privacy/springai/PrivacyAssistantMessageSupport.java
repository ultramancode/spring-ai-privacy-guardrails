package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.content.Media;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Internal support for exact Spring AI assistant-message types used by known providers. */
final class PrivacyAssistantMessageSupport {

    // Compare names first so applications without the optional DeepSeek JAR can load this class.
    private static final String DEEPSEEK_ASSISTANT_MESSAGE_CLASS_NAME =
            "org.springframework.ai.deepseek.DeepSeekAssistantMessage";

    private PrivacyAssistantMessageSupport() {
    }

    static void requireSupported(AssistantMessage message, PrivacyPhase phase) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        if (message.getClass() == AssistantMessage.class || isDeepSeekMessage(message)) {
            return;
        }
        throw new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                phase,
                "Unsupported Spring AI Message implementation"
        );
    }

    static String providerSpecificText(AssistantMessage message) {
        return isDeepSeekMessage(message)
                ? PrivacyDeepSeekMessageSupport.reasoningContent(message)
                : null;
    }

    static AssistantMessage rebuild(
            AssistantMessage source,
            String content,
            Map<String, Object> metadata,
            List<AssistantMessage.ToolCall> toolCalls,
            List<Media> media,
            String providerSpecificText
    ) {
        if (isDeepSeekMessage(source)) {
            return PrivacyDeepSeekMessageSupport.rebuild(
                    source,
                    content,
                    metadata,
                    toolCalls,
                    media,
                    providerSpecificText
            );
        }
        return AssistantMessage.builder()
                .content(content)
                .properties(metadata)
                .toolCalls(toolCalls)
                .media(media)
                .build();
    }

    static boolean haveSameRuntimeType(AssistantMessage left, AssistantMessage right) {
        return left.getClass() == right.getClass();
    }

    private static boolean isDeepSeekMessage(AssistantMessage message) {
        return message.getClass().getName().equals(DEEPSEEK_ASSISTANT_MESSAGE_CLASS_NAME)
                && PrivacyDeepSeekMessageSupport.isExactType(message);
    }
}
