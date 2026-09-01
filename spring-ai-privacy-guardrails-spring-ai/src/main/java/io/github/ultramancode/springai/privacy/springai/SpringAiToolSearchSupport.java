package io.github.ultramancode.springai.privacy.springai;

import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * Recognizes Spring AI's built-in Tool Search tool callback by its reserved name
 * and request-scoped session context marker.
 */
public final class SpringAiToolSearchSupport {

    private static final String TOOL_SEARCH_TOOL_NAME = "toolSearchTool";
    private static final String SESSION_ID_CONTEXT_KEY = "toolSearchToolSessionId";

    private SpringAiToolSearchSupport() {
    }

    /**
     * Returns whether the callback and options identify Spring AI's Tool Search tool callback.
     *
     * @param callback current callback
     * @param options current tool-calling options
     * @return {@code true} when the Tool Search name and session marker are present
     */
    public static boolean isToolSearchToolCallback(
            ToolCallback callback,
            ToolCallingChatOptions options
    ) {
        if (callback == null || options == null) {
            return false;
        }
        ToolDefinition definition = callback.getToolDefinition();
        if (definition == null || !TOOL_SEARCH_TOOL_NAME.equals(definition.name())) {
            return false;
        }
        Map<String, Object> toolContext = options.getToolContext();
        return toolContext != null
                && toolContext.get(SESSION_ID_CONTEXT_KEY) != null;
    }
}
