package io.github.ultramancode.springai.privacy.security;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Captures request Authentication and initial tool callbacks in a request-scoped registry, then
 * attaches only an opaque handle to Spring AI tool options.
 */
final class SpringSecurityContextAdvisor implements CallAdvisor, StreamAdvisor {

    static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    private final SecurityToolSessionRegistry registry;
    private final SecurityContextHolderStrategy contextHolderStrategy;

    SpringSecurityContextAdvisor(
            SecurityToolSessionRegistry registry,
            SecurityContextHolderStrategy contextHolderStrategy
    ) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.contextHolderStrategy = Objects.requireNonNull(
                contextHolderStrategy,
                "contextHolderStrategy must not be null"
        );
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        List<ToolCallback> callbacks = toolCallbacks(request);
        if (callbacks.isEmpty()) {
            return chain.nextCall(request);
        }
        Authentication authentication = currentAuthentication();
        if (authentication == null) {
            throw SecurityToolSessionRegistry.denied(
                    "Tool authorization requires an Authentication"
            );
        }
        try (SecurityToolSession session = this.registry.open(authentication, callbacks)) {
            return chain.nextCall(attachHandle(request, session.handle()));
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request,
            StreamAdvisorChain chain
    ) {
        List<ToolCallback> callbacks = toolCallbacks(request);
        if (callbacks.isEmpty()) {
            return chain.nextStream(request);
        }
        Authentication threadAuthentication = currentAuthentication();
        Mono<Authentication> authentication = Mono.deferContextual(contextView -> {
            if (!contextView.hasKey(SecurityContext.class)) {
                return Mono.justOrEmpty(threadAuthentication);
            }
            return ReactiveSecurityContextHolder.getContext()
                    .flatMap(context -> {
                        Authentication reactiveAuthentication = context.getAuthentication();
                        if (reactiveAuthentication == null) {
                            return missingAuthentication();
                        }
                        return Mono.just(reactiveAuthentication);
                    })
                    .switchIfEmpty(missingAuthentication());
        });
        return authentication
                .switchIfEmpty(missingAuthentication())
                .flatMapMany(value -> Flux.using(
                        () -> this.registry.open(value, callbacks),
                        session -> chain.nextStream(attachHandle(request, session.handle())),
                        SecurityToolSession::close
                ));
    }

    @Override
    public String getName() {
        return "SpringSecurityContextAdvisor";
    }

    @Override
    public int getOrder() {
        return DEFAULT_ORDER;
    }

    private Authentication currentAuthentication() {
        return this.contextHolderStrategy.getContext().getAuthentication();
    }

    private static Mono<Authentication> missingAuthentication() {
        return Mono.error(SecurityToolSessionRegistry.denied(
                "Tool authorization requires an Authentication"
        ));
    }

    private static List<ToolCallback> toolCallbacks(ChatClientRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options)
                || options.getToolCallbacks() == null) {
            return List.of();
        }
        return List.copyOf(options.getToolCallbacks());
    }

    private static ChatClientRequest attachHandle(
            ChatClientRequest request,
            SecurityToolContextHandle handle
    ) {
        ToolCallingChatOptions options = (ToolCallingChatOptions) request.prompt().getOptions();
        Map<String, Object> toolContext = new HashMap<>();
        if (options.getToolContext() != null) {
            toolContext.putAll(options.getToolContext());
        }
        toolContext.put(SecurityToolSessionRegistry.TOOL_CONTEXT_HANDLE, handle);
        ToolCallingChatOptions securedOptions = options.mutate().toolContext(toolContext).build();
        Prompt prompt = new Prompt(new ArrayList<>(request.prompt().getInstructions()), securedOptions);
        return request.mutate().prompt(prompt).build();
    }
}
