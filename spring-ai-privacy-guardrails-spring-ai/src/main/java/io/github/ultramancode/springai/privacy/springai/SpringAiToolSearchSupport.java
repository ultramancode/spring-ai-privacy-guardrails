package io.github.ultramancode.springai.privacy.springai;

import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * Recognizes Spring AI's built-in, non-disclosing Tool Search control callback.
 * The callback name alone is not sufficient: Spring AI must also have installed
 * its public Tool Search session context entry for the current iteration.
 *
 * @since 0.3.0
 */
public final class SpringAiToolSearchSupport {

    private static final String CONTROL_TOOL_NAME = "toolSearchTool";
    private static final String SESSION_ID_CONTEXT_KEY = "toolSearchToolSessionId";

    private SpringAiToolSearchSupport() {
    }

    /**
     * Returns whether the callback and options carry Spring AI's Tool Search control contract.
     *
     * @param callback current callback
     * @param options current tool-calling options
     * @return {@code true} for the built-in Tool Search control contract
     */
    public static boolean isControlCallback(
            ToolCallback callback,
            ToolCallingChatOptions options
    ) {
        if (callback == null || options == null) {
            return false;
        }
        ToolDefinition definition = callback.getToolDefinition();
        if (definition == null || !CONTROL_TOOL_NAME.equals(definition.name())) {
            return false;
        }
        Map<String, Object> toolContext = options.getToolContext();
        return toolContext != null
                && toolContext.get(SESSION_ID_CONTEXT_KEY) != null;
    }
}
