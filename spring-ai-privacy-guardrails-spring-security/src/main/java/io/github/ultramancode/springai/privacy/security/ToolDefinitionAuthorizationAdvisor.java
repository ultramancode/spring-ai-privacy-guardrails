package io.github.ultramancode.springai.privacy.security;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Removes unauthorized tool callbacks before each model call without replacing
 * the model's existing {@link ToolCallingManager}.
 * Uses the authorization-aware ToolCallingManager supplied by
 * {@link SpringSecurityToolBoundary#toolCallingManager()}.
 */
final class ToolDefinitionAuthorizationAdvisor implements CallAdvisor, StreamAdvisor {

    // The combined factory registers the privacy model boundary first at this same order.
    // Privacy validates the callback snapshot before this advisor removes unauthorized callbacks.
    static final int DEFAULT_ORDER = Ordered.LOWEST_PRECEDENCE - 1;

    private final ToolCallingManager toolCallingManager;

    ToolDefinitionAuthorizationAdvisor(ToolCallingManager toolCallingManager) {
        this.toolCallingManager = Objects.requireNonNull(toolCallingManager, "toolCallingManager must not be null");
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        return chain.nextCall(authorizeDefinitions(request));
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> chain.nextStream(authorizeDefinitions(request)));
    }

    private ChatClientRequest authorizeDefinitions(ChatClientRequest request) {
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

    @Override
    public String getName() {
        return "ToolDefinitionAuthorizationAdvisor";
    }

    @Override
    public int getOrder() {
        return DEFAULT_ORDER;
    }
}
