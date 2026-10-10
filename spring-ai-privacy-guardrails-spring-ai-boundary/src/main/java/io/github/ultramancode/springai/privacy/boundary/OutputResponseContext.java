package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Internal context markers shared by Privacy output protection and output inspection.
 * Tracks privacy processing for each response and identifies complete tool responses.
 * Applications should use the feature configurers.
 *
 * @hidden
 */
public final class OutputResponseContext {
    private static final String PRIVACY_PROCESSING_KEY = "io.github.ultramancode.springai.privacy.output-processing";
    private static final String TOOL_RESPONSE_KEY = "io.github.ultramancode.springai.privacy.output-tool-response";
    private static final Object PRIVACY_TRACKING_REQUESTED = new Object();
    private static final Object COMPLETE_TOOL_RESPONSE = new Object();

    private OutputResponseContext() {
    }

    /** Requests a marker when Privacy output processing completes. */
    public static ChatClientRequest requestPrivacyTracking(ChatClientRequest request) {
        return request.mutate().context(PRIVACY_PROCESSING_KEY, PRIVACY_TRACKING_REQUESTED).build();
    }

    public static boolean isPrivacyTrackingRequested(ChatClientRequest request) {
        return request.context().get(PRIVACY_PROCESSING_KEY) == PRIVACY_TRACKING_REQUESTED;
    }

    /** Marks a complete returnDirect or tool-call-limit response emitted by the tool advisor. */
    public static ChatClientResponse markCompleteToolResponse(ChatClientResponse response) {
        return response.mutate().context(TOOL_RESPONSE_KEY, COMPLETE_TOOL_RESPONSE).build();
    }

    /** Complete tool responses are separate text units from preceding model fragments. */
    public static boolean isCompleteToolResponse(ChatClientResponse response) {
        return response.context().get(TOOL_RESPONSE_KEY) == COMPLETE_TOOL_RESPONSE;
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

    /** Removes privacy tracking while preserving markers for complete tool responses. */
    public static ChatClientResponse clearPrivacyTracking(ChatClientResponse response) {
        if (!response.context().containsKey(PRIVACY_PROCESSING_KEY)) {
            return response;
        }
        Map<String, Object> context = new HashMap<>(response.context());
        context.remove(PRIVACY_PROCESSING_KEY);
        return new ChatClientResponse(response.chatResponse(), context);
    }

    /** Removes privacy tracking and markers for complete tool responses. */
    public static ChatClientResponse clearInternalMarkers(ChatClientResponse response) {
        ChatClientResponse cleared = clearPrivacyTracking(response);
        if (!cleared.context().containsKey(TOOL_RESPONSE_KEY)) {
            return cleared;
        }
        Map<String, Object> context = new HashMap<>(cleared.context());
        context.remove(TOOL_RESPONSE_KEY);
        return new ChatClientResponse(cleared.chatResponse(), context);
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
