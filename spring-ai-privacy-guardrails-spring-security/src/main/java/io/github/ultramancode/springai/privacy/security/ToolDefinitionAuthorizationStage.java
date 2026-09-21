package io.github.ultramancode.springai.privacy.security;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Removes unauthorized tool callbacks before each model call without replacing
 * the model's existing {@link ToolCallingManager}.
 * Uses the authorization-aware ToolCallingManager supplied by
 * {@link SpringSecurityToolBoundary#toolCallingManager()}.
 */
final class ToolDefinitionAuthorizationStage implements UnaryOperator<ChatClientRequest> {

    private final ToolCallingManager toolCallingManager;

    ToolDefinitionAuthorizationStage(ToolCallingManager toolCallingManager) {
        this.toolCallingManager = Objects.requireNonNull(toolCallingManager, "toolCallingManager must not be null");
    }

    @Override
    public ChatClientRequest apply(ChatClientRequest request) {
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options)) {
            return request;
        }
        if (options.getToolCallbacks() == null || options.getToolCallbacks().isEmpty()) {
            return request;
        }
        Set<String> authorizedNames = this.toolCallingManager.resolveToolDefinitions(options)
                .stream().map(ToolDefinition::name).collect(Collectors.toSet());
        List<ToolCallback> authorizedCallbacks = options.getToolCallbacks().stream()
                .filter(callback -> authorizedNames.contains(callback.getToolDefinition().name()))
                .toList();
        ToolCallingChatOptions authorizedOptions = options.mutate()
                .toolCallbacks(authorizedCallbacks).build();
        Prompt authorizedPrompt = new Prompt(
                new ArrayList<>(request.prompt().getInstructions()), authorizedOptions);
        return request.mutate().prompt(authorizedPrompt).build();
    }

}
