package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Internal response support shared by Privacy output protection and output inspection.
 * Tracks Privacy processing for each response and prepares text with simplified PII tokens for output inspection.
 * Also identifies complete tool responses.
 * Applications should use the feature configurers.
 *
 * @hidden
 */
public final class OutputResponseContext {
    private static final String PRIVACY_PROCESSING_KEY = "io.github.ultramancode.springai.privacy.output-processing";
    // ToolCallLimitExceededException is unavailable in the supported Spring AI 2.0.0 baseline.
    private static final String TOOL_CALL_LIMIT_EXCEEDED = "toolCallLimitExceeded";

    private OutputResponseContext() {
    }

    /**
     * Requests a marker on responses after Privacy output processing succeeds.
     * When simplification is requested, the same request marker asks for both tracking and
     * preparation of text with simplified PII tokens for output inspection.
     *
     * @param chatClientRequest request to attach the tracking request to
     * @param simplifyPiiTokens {@code true} to request tracking and preparation of simplified inspection text,
     *                         or {@code false} to request tracking only
     * @return a new request containing the tracking request marker
     */
    public static ChatClientRequest requestPrivacyTracking(
            ChatClientRequest chatClientRequest,
            boolean simplifyPiiTokens
    ) {
        ChatClientRequest trackedRequest = chatClientRequest.mutate()
                .context(PRIVACY_PROCESSING_KEY, new TrackingRequest(simplifyPiiTokens)).build();
        captureUserMessage(trackedRequest, false);
        return trackedRequest;
    }

    /** Returns whether tracking is requested, including requests that also prepare simplified inspection text. */
    public static boolean isPrivacyTrackingRequested(ChatClientRequest chatClientRequest) {
        return chatClientRequest.context().get(PRIVACY_PROCESSING_KEY) instanceof TrackingRequest;
    }

    public static boolean isPiiTokenSimplificationRequested(ChatClientRequest chatClientRequest) {
        return chatClientRequest.context().get(PRIVACY_PROCESSING_KEY) instanceof TrackingRequest tracking
                && tracking.simplifyPiiTokens;
    }

    /**
     * Captures the latest user text while preparing the model request.
     * Privacy processing calls this with {@code true} after transformation succeeds.
     * The model boundary calls it again to capture any later changes. Unchanged text retains its status.
     * Token simplification runs now, while the request's privacy session is active.
     */
    public static void captureUserMessage(ChatClientRequest request, boolean privacyProcessed) {
        if (!(request.context().get(PRIVACY_PROCESSING_KEY) instanceof TrackingRequest tracking)) {
            return;
        }
        List<Message> messages = request.prompt().getInstructions();
        String text = null;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof UserMessage userMessage) {
                text = userMessage.getText();
                break;
            }
        }
        if (text == null) {
            tracking.userMessage = null;
            return;
        }
        UserMessageContext previous = tracking.userMessage;
        if (!privacyProcessed && previous != null && text.equals(previous.text())) {
            return;
        }
        String simplifiedText = null;
        if (privacyProcessed && tracking.simplifyPiiTokens) {
            UnaryOperator<String> simplifier = PiiTokenSimplifierContext.findSimplifier(request);
            if (simplifier != null) {
                simplifiedText = simplifier.apply(text);
            }
        }
        tracking.userMessage = new UserMessageContext(text, privacyProcessed, simplifiedText);
    }

    /** Returns the captured user message, or {@code null} when the request has no user text. */
    public static UserMessageContext findUserMessage(ChatClientRequest request) {
        if (request.context().get(PRIVACY_PROCESSING_KEY) instanceof TrackingRequest tracking) {
            return tracking.userMessage;
        }
        return null;
    }

    /** Snapshot used by output inspection after the privacy session closes. */
    public record UserMessageContext(String text, boolean privacyProcessed, String simplifiedText) {
        @Override
        public String toString() {
            return "UserMessageContext[content=<redacted>]";
        }
    }

    private static final class TrackingRequest {
        private final boolean simplifyPiiTokens;
        private volatile UserMessageContext userMessage;

        private TrackingRequest(boolean simplifyPiiTokens) {
            this.simplifyPiiTokens = simplifyPiiTokens;
        }

        @Override
        public String toString() {
            return "TrackingRequest[content=<redacted>]";
        }
    }

    /**
     * Marks the response after its configured Privacy output policy succeeds.
     * When a simplifier is supplied, prepares text for inspection and stores it separately in context.
     * This preparation must run while the simplifier's privacy session is still active.
     *
     * @param chatClientResponse response after Privacy output processing
     * @param piiTokenSimplifier function to simplify PII tokens, or {@code null} to skip preparing inspection text
     * @return a new response containing the processing marker and any prepared text mapping
     */
    public static ChatClientResponse markPrivacyProcessed(
            ChatClientResponse chatClientResponse,
            UnaryOperator<String> piiTokenSimplifier
    ) {
        ChatResponse chatResponse = chatClientResponse.chatResponse();
        Map<String, String> simplifiedTextsByOriginalText = prepareSimplifiedTexts(chatResponse, piiTokenSimplifier);
        PrivacyProcessingMarker privacyProcessingMarker =
                new PrivacyProcessingMarker(chatResponse, simplifiedTextsByOriginalText);
        return chatClientResponse.mutate()
                .context(PRIVACY_PROCESSING_KEY, privacyProcessingMarker)
                .build();
    }

    /** Checks whether the Privacy processing marker belongs to this response. */
    public static boolean isPrivacyProcessed(ChatClientResponse chatClientResponse) {
        Object marker = chatClientResponse.context().get(PRIVACY_PROCESSING_KEY);
        if (!(marker instanceof PrivacyProcessingMarker privacyMarker)) {
            return false;
        }

        ChatResponse chatResponse = chatClientResponse.chatResponse();
        if (privacyMarker.chatResponse() != chatResponse) {
            return false;
        }

        return chatResponse == null || chatResponse.getClass() == ChatResponse.class;
    }

    /**
     * Returns prepared inspection text only when the processing marker belongs to this response.
     *
     * @param chatClientResponse response whose prepared inspection text is requested
     * @return an unmodifiable map from output text before token simplification to its simplified text,
     *         or {@code null} if no valid processing marker exists or no simplifier was supplied.
     *         An empty map means a simplifier was supplied but no non-null output text was available.
     */
    public static Map<String, String> findSimplifiedTextsByOriginalText(ChatClientResponse chatClientResponse) {
        if (!isPrivacyProcessed(chatClientResponse)) {
            return null;
        }
        PrivacyProcessingMarker privacyProcessingMarker =
                (PrivacyProcessingMarker) chatClientResponse.context().get(PRIVACY_PROCESSING_KEY);
        return privacyProcessingMarker.simplifiedTextsByOriginalText();
    }

    /** Removes internal privacy tracking from the response context. */
    public static ChatClientResponse clearPrivacyTracking(ChatClientResponse chatClientResponse) {
        if (!chatClientResponse.context().containsKey(PRIVACY_PROCESSING_KEY)) {
            return chatClientResponse;
        }
        Map<String, Object> context = new HashMap<>(chatClientResponse.context());
        context.remove(PRIVACY_PROCESSING_KEY);
        return new ChatClientResponse(chatClientResponse.chatResponse(), context);
    }

    /** Complete tool responses are separate text units from preceding model fragments. */
    public static boolean isCompleteToolResponse(Generation generation) {
        String finishReason = generation.getMetadata().getFinishReason();
        return ToolExecutionResult.FINISH_REASON.equals(finishReason)
                || TOOL_CALL_LIMIT_EXCEEDED.equals(finishReason);
    }

    /** Thought text is processed separately from answer text. */
    public static boolean isThought(AssistantMessage message) {
        return Boolean.TRUE.equals(message.getMetadata().get("isThought"))
                || Boolean.TRUE.equals(message.getMetadata().get("thinking"));
    }

    /**
     * Returns {@code null} when no simplifier is supplied.
     * Returns an unmodifiable map from response text to simplified text when a simplifier is supplied.
     * The map is empty when there is no output text to simplify.
     */
    private static Map<String, String> prepareSimplifiedTexts(
            ChatResponse chatResponse,
            UnaryOperator<String> piiTokenSimplifier
    ) {
        if (piiTokenSimplifier == null) {
            return null;
        }
        if (chatResponse == null) {
            return Map.of();
        }

        Map<String, String> simplifiedTextsByOriginalText = new HashMap<>();
        for (Generation generation : chatResponse.getResults()) {
            String originalText = generation.getOutput().getText();
            if (originalText == null) {
                continue;
            }
            String simplifiedText = piiTokenSimplifier.apply(originalText);
            simplifiedTextsByOriginalText.put(originalText, simplifiedText);
        }
        return Map.copyOf(simplifiedTextsByOriginalText);
    }

    private record PrivacyProcessingMarker(
            ChatResponse chatResponse,
            Map<String, String> simplifiedTextsByOriginalText
    ) {
        @Override
        public String toString() {
            return "PrivacyProcessingMarker[content=<redacted>]";
        }
    }
}
