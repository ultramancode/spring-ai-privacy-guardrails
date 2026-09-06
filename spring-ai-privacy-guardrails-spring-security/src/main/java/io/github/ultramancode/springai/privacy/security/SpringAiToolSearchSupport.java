package io.github.ultramancode.springai.privacy.security;

import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Map;

/**
 * Matches Spring AI's reserved Tool Search name and request-scoped session context marker.
 * This compatibility convention trusts application extensions and does not verify callback origin.
 */
final class SpringAiToolSearchSupport {

    private static final String TOOL_SEARCH_TOOL_NAME = "toolSearchTool";
    private static final String SESSION_ID_CONTEXT_KEY = "toolSearchToolSessionId";

    private SpringAiToolSearchSupport() {
    }

    static boolean isToolSearchToolCallback(
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
