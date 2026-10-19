package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

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
    private static final Object PII_TOKEN_SIMPLIFICATION_REQUESTED = new Object();

    private OutputResponseContext() {
    }

    /** Requests tracking of Privacy output processing. */
    public static ChatClientRequest requestPrivacyTracking(ChatClientRequest request, boolean simplifyPiiTokens) {
        Object marker = PRIVACY_TRACKING_REQUESTED;
        if (simplifyPiiTokens) {
            marker = PII_TOKEN_SIMPLIFICATION_REQUESTED;
        }
        return request.mutate().context(PRIVACY_PROCESSING_KEY, marker).build();
    }

    public static boolean isPrivacyTrackingRequested(ChatClientRequest request) {
        Object marker = request.context().get(PRIVACY_PROCESSING_KEY);
        return marker == PRIVACY_TRACKING_REQUESTED || marker == PII_TOKEN_SIMPLIFICATION_REQUESTED;
    }

    public static boolean isPiiTokenSimplificationRequested(ChatClientRequest request) {
        return request.context().get(PRIVACY_PROCESSING_KEY) == PII_TOKEN_SIMPLIFICATION_REQUESTED;
    }

    /** Complete tool responses are separate text units from preceding model fragments. */
    public static boolean isCompleteToolResponse(Generation generation) {
        String finishReason = generation.getMetadata().getFinishReason();
        return ToolExecutionResult.FINISH_REASON.equals(finishReason)
                || TOOL_CALL_LIMIT_EXCEEDED.equals(finishReason);
    }

    /**
     * Marks the response after its configured Privacy output policy succeeds.
     * Prepares inspection copies when requested, while the owning privacy session is still active.
     */
    public static ChatClientResponse markPrivacyProcessed(
            ChatClientResponse response, UnaryOperator<String> piiTokenSimplifier) {
        Map<String, String> simplifiedTexts = null;
        if (piiTokenSimplifier != null) {
            Map<String, String> texts = new HashMap<>();
            if (response.chatResponse() != null) {
                for (Generation generation : response.chatResponse().getResults()) {
                    String text = generation.getOutput().getText();
                    if (text != null) {
                        texts.put(text, piiTokenSimplifier.apply(text));
                    }
                }
            }
            simplifiedTexts = Map.copyOf(texts);
        }
        return response.mutate()
                .context(PRIVACY_PROCESSING_KEY, new PrivacyProcessingMarker(response.chatResponse(), simplifiedTexts))
                .build();
    }

    /** Returns prepared text copies only when the marker still belongs to this response. */
    public static Map<String, String> simplifiedPiiTexts(ChatClientResponse response) {
        if (!isPrivacyProcessed(response)) {
            return null;
        }
        PrivacyProcessingMarker marker = (PrivacyProcessingMarker) response.context().get(PRIVACY_PROCESSING_KEY);
        return marker.simplifiedTexts();
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

    /** Thought text is processed separately from answer text. */
    public static boolean isThought(AssistantMessage message) {
        return Boolean.TRUE.equals(message.getMetadata().get("isThought"))
                || Boolean.TRUE.equals(message.getMetadata().get("thinking"));
    }

    private record PrivacyProcessingMarker(ChatResponse response, Map<String, String> simplifiedTexts) {
        @Override
        public String toString() {
            return "PrivacyProcessingMarker[content=<redacted>]";
        }
    }
}
