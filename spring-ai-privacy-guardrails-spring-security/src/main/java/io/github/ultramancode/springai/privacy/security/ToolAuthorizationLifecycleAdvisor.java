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
import java.util.List;
import java.util.Objects;

/**
 * Owns the lifecycle of request-scoped tool-authorization state. It captures the request
 * {@link Authentication} and initial tool callbacks in an internal registry, attaches only an
 * opaque handle to Spring AI tool options, and removes the state when the advised call terminates.
 */
final class ToolAuthorizationLifecycleAdvisor implements CallAdvisor, StreamAdvisor {

    // Run inside the default privacy lifecycle (HIGHEST_PRECEDENCE) when both protections are enabled.
    static final int DEFAULT_ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    private final ToolAuthorizationSessionRegistry sessionRegistry;
    private final SecurityContextHolderStrategy contextHolderStrategy;

    ToolAuthorizationLifecycleAdvisor(
            ToolAuthorizationSessionRegistry sessionRegistry,
            SecurityContextHolderStrategy contextHolderStrategy
    ) {
        this.sessionRegistry = Objects.requireNonNull(
                sessionRegistry,
                "sessionRegistry must not be null"
        );
        this.contextHolderStrategy = Objects.requireNonNull(
                contextHolderStrategy,
                "contextHolderStrategy must not be null"
        );
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        List<ToolCallback> initialToolCallbacks = toolCallbacks(request);
        if (initialToolCallbacks.isEmpty()) {
            return chain.nextCall(request);
        }
        Authentication authentication = currentAuthentication();
        if (authentication == null) {
            throw ToolAuthorizationSessionRegistry.denied(
                    "Tool authorization requires an Authentication"
            );
        }
        try (ToolAuthorizationSession session =
                     this.sessionRegistry.openSession(authentication, initialToolCallbacks)) {
            return chain.nextCall(attachAuthorizationSessionHandle(request, session.handle()));
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request,
            StreamAdvisorChain chain
    ) {
        List<ToolCallback> initialToolCallbacks = toolCallbacks(request);
        if (initialToolCallbacks.isEmpty()) {
            return chain.nextStream(request);
        }
        Authentication fallbackAuthentication = currentAuthentication();
        return resolveStreamAuthentication(fallbackAuthentication)
                .flatMapMany(authentication -> Flux.using(
                        () -> this.sessionRegistry.openSession(
                                authentication,
                                initialToolCallbacks
                        ),
                        session -> chain.nextStream(
                                attachAuthorizationSessionHandle(request, session.handle())
                        ),
                        ToolAuthorizationSession::close
                ));
    }

    private static Mono<Authentication> resolveStreamAuthentication(Authentication fallbackAuthentication) {
        return Mono.deferContextual(contextView -> {
            if (!contextView.hasKey(SecurityContext.class)) {
                return Mono.justOrEmpty(fallbackAuthentication);
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
        }).switchIfEmpty(missingAuthentication());
    }

    @Override
    public String getName() {
        return "ToolAuthorizationLifecycleAdvisor";
    }

    @Override
    public int getOrder() {
        return DEFAULT_ORDER;
    }

    private Authentication currentAuthentication() {
        return this.contextHolderStrategy.getContext().getAuthentication();
    }

    private static Mono<Authentication> missingAuthentication() {
        return Mono.error(ToolAuthorizationSessionRegistry.denied(
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

    private static ChatClientRequest attachAuthorizationSessionHandle(
            ChatClientRequest request,
            ToolAuthorizationSessionHandle handle
    ) {
        ToolCallingChatOptions options = (ToolCallingChatOptions) request.prompt().getOptions();
        ToolCallingChatOptions optionsWithAuthorizationSession = options.mutate()
                .toolContext(
                        ToolAuthorizationSessionRegistry.TOOL_AUTHORIZATION_SESSION_HANDLE_KEY,
                        handle
                )
                .build();
        Prompt promptWithAuthorizationSession = new Prompt(
                new ArrayList<>(request.prompt().getInstructions()),
                optionsWithAuthorizationSession
        );
        return request.mutate().prompt(promptWithAuthorizationSession).build();
    }
}
