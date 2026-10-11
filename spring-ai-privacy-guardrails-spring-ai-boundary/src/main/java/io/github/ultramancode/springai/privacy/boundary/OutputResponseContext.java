package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Internal response support shared by Privacy output protection and output inspection.
 * Tracks privacy processing for each response and identifies complete tool responses.
 * Applications should use the feature configurers.
 *
 * @hidden
 */
public final class OutputResponseContext {
    private static final String PRIVACY_PROCESSING_KEY = "io.github.ultramancode.springai.privacy.output-processing";
    // ToolCallLimitExceededException is unavailable in the supported Spring AI 2.0.0 baseline.
    private static final String TOOL_CALL_LIMIT_EXCEEDED = "toolCallLimitExceeded";
    private static final Object PRIVACY_TRACKING_REQUESTED = new Object();

    private OutputResponseContext() {
    }

    /** Requests a marker when Privacy output processing completes. */
    public static ChatClientRequest requestPrivacyTracking(ChatClientRequest request) {
        return request.mutate().context(PRIVACY_PROCESSING_KEY, PRIVACY_TRACKING_REQUESTED).build();
    }

    public static boolean isPrivacyTrackingRequested(ChatClientRequest request) {
        return request.context().get(PRIVACY_PROCESSING_KEY) == PRIVACY_TRACKING_REQUESTED;
    }

    /** Complete tool responses are separate text units from preceding model fragments. */
    public static boolean isCompleteToolResponse(Generation generation) {
        String finishReason = generation.getMetadata().getFinishReason();
        return ToolExecutionResult.FINISH_REASON.equals(finishReason)
                || TOOL_CALL_LIMIT_EXCEEDED.equals(finishReason);
    }

    /** Marks the response after its configured Privacy output policy succeeds. */
    public static ChatClientResponse markPrivacyProcessed(ChatClientResponse response) {
        return response.mutate()
                .context(PRIVACY_PROCESSING_KEY, new PrivacyProcessingMarker(response.chatResponse()))
                .build();
    }

    /** Checks whether the Privacy processing marker belongs to this response. */
    public static boolean isPrivacyProcessed(ChatClientResponse response) {
        Object marker = response.context().get(PRIVACY_PROCESSING_KEY);
        if (!(marker instanceof PrivacyProcessingMarker privacyMarker)) {
            return false;
        }

        ChatResponse chatResponse = response.chatResponse();
        if (privacyMarker.response() != chatResponse) {
            return false;
        }

        return chatResponse == null || chatResponse.getClass() == ChatResponse.class;
    }

    /** Removes internal privacy tracking from the response context. */
    public static ChatClientResponse clearPrivacyTracking(ChatClientResponse response) {
        if (!response.context().containsKey(PRIVACY_PROCESSING_KEY)) {
            return response;
        }
        Map<String, Object> context = new HashMap<>(response.context());
        context.remove(PRIVACY_PROCESSING_KEY);
        return new ChatClientResponse(response.chatResponse(), context);
    }

    /** Text-bearing thought frames form a separate logical unit from answer frames. */
    public static boolean isThought(AssistantMessage message) {
        return Boolean.TRUE.equals(message.getMetadata().get("isThought"))
                || Boolean.TRUE.equals(message.getMetadata().get("thinking"));
    }

    private record PrivacyProcessingMarker(ChatResponse response) {
        @Override
        public String toString() {
            return "PrivacyProcessingMarker[content=<redacted>]";
        }
    }
}
