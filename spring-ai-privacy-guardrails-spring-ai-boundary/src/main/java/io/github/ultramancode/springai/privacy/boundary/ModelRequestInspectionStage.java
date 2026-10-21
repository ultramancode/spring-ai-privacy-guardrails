package io.github.ultramancode.springai.privacy.boundary;

import org.springframework.ai.chat.client.ChatClientRequest;

/**
 * Inspects a prepared model request before each model call, including calls after tool execution.
 */
@FunctionalInterface
public interface ModelRequestInspectionStage {

    /**
     * Inspects the request without replacing it.
     * Returning normally allows request processing to continue.
     * Throw a runtime exception to stop the request before the model call.
     *
     * @param chatClientRequest the request about to be sent to the model
     * @param streaming whether this is a streaming request
     * @throws RuntimeException if inspection rejects the request or cannot complete
     */
    void inspect(ChatClientRequest chatClientRequest, boolean streaming);
}
