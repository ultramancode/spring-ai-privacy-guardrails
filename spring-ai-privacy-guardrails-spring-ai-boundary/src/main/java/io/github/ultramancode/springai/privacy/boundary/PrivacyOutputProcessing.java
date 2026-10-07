package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.HashMap;
import java.util.Map;

/**
 * Internal cooperation between final output inspection and the Privacy lifecycle.
 * Completion is bound to the returned response, never inferred from configuration or input processing.
 * No privacy session or token mappings are retained. Applications should use the feature configurers.
 *
 * @hidden
 */
public final class PrivacyOutputProcessing {
    private static final String CONTEXT_KEY = "io.github.ultramancode.springai.privacy.output-processing";
    private static final Object REQUESTED = new Object();

    private PrivacyOutputProcessing() {
    }

    /** Requests completion tracking for one output-inspected invocation. */
    public static ChatClientRequest requestCompletion(ChatClientRequest request) {
        return request.mutate().context(CONTEXT_KEY, REQUESTED).build();
    }

    public static boolean completionRequested(ChatClientRequest request) {
        return request.context().get(CONTEXT_KEY) == REQUESTED;
    }

    /** Called only after the configured output policy successfully completes. */
    public static ChatClientResponse completed(ChatClientResponse response) {
        return response.mutate().context(CONTEXT_KEY, new Completion(response.chatResponse())).build();
    }

    /** Replacement responses cannot reuse completion from the original response. */
    public static boolean hasCompleted(ChatClientResponse response) {
        Object marker = response.context().get(CONTEXT_KEY);
        ChatResponse chatResponse = response.chatResponse();
        return marker instanceof Completion completion && completion.response() == chatResponse
                && (chatResponse == null || chatResponse.getClass() == ChatResponse.class);
    }

    /** Removes tracking before a response leaves the output inspection boundary. */
    public static ChatClientResponse clear(ChatClientResponse response) {
        if (!response.context().containsKey(CONTEXT_KEY)) {
            return response;
        }
        Map<String, Object> context = new HashMap<>(response.context());
        context.remove(CONTEXT_KEY);
        return new ChatClientResponse(response.chatResponse(), context);
    }

    /** Text-bearing thought frames form a separate logical unit from answer frames. */
    public static boolean isThought(AssistantMessage message) {
        return Boolean.TRUE.equals(message.getMetadata().get("isThought"))
                || Boolean.TRUE.equals(message.getMetadata().get("thinking"));
    }

    private record Completion(ChatResponse response) {
        @Override
        public String toString() {
            return "OutputProcessingCompletion[content=<redacted>]";
        }
    }
}
