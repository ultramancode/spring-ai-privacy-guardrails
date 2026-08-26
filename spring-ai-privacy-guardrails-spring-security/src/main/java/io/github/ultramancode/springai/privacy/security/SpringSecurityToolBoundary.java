package io.github.ultramancode.springai.privacy.security;

import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

import java.util.Objects;

/**
 * Creates the manager and advisor that share one request-scoped tool authorization registry.
 * Applications must install both returned components for the boundary to be complete.
 */
public final class SpringSecurityToolBoundary {

    private final SecurityToolSessionRegistry registry;
    private final ToolCallingManager toolCallingManager;
    private final Advisor advisor;

    private SpringSecurityToolBoundary(
            ToolCallingManager delegate,
            AuthorizationManager<ToolAuthorizationContext> authorizationManager,
            SecurityContextHolderStrategy contextHolderStrategy
    ) {
        this.registry = new SecurityToolSessionRegistry();
        this.toolCallingManager = new AuthorizationAwareToolCallingManager(
                authorizationManager,
                this.registry,
                delegate
        );
        this.advisor = new SpringSecurityContextAdvisor(this.registry, contextHolderStrategy);
    }

    /**
     * Starts a boundary builder that decorates an existing Spring AI manager.
     *
     * @param delegate existing manager whose resolver, observations, exception processing,
     *                 and execution limits must be preserved
     * @param authorizationManager policy evaluated for definition and execution phases
     * @return boundary builder
     */
    public static Builder builder(
            ToolCallingManager delegate,
            AuthorizationManager<ToolAuthorizationContext> authorizationManager
    ) {
        return new Builder(delegate, authorizationManager);
    }

    /** Manager that must be shared by the ChatModel and ToolCallingAdvisor. */
    public ToolCallingManager toolCallingManager() {
        return this.toolCallingManager;
    }

    /** Advisor that captures blocking or reactive SecurityContext for one request. */
    public Advisor advisor() {
        return this.advisor;
    }

    int activeSessionCount() {
        return this.registry.activeSessionCount();
    }

    /** Builder for a complete optional Spring Security tool boundary. */
    public static final class Builder {

        private final ToolCallingManager delegate;
        private final AuthorizationManager<ToolAuthorizationContext> authorizationManager;
        private SecurityContextHolderStrategy contextHolderStrategy =
                SecurityContextHolder.getContextHolderStrategy();

        private Builder(
                ToolCallingManager delegate,
                AuthorizationManager<ToolAuthorizationContext> authorizationManager
        ) {
            this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
            this.authorizationManager = Objects.requireNonNull(
                    authorizationManager,
                    "authorizationManager must not be null"
            );
        }

        /** Uses an application-selected blocking SecurityContext strategy. */
        public Builder securityContextHolderStrategy(
                SecurityContextHolderStrategy contextHolderStrategy
        ) {
            this.contextHolderStrategy = Objects.requireNonNull(
                    contextHolderStrategy,
                    "contextHolderStrategy must not be null"
            );
            return this;
        }

        /** Builds one manager/advisor pair backed by a private request registry. */
        public SpringSecurityToolBoundary build() {
            return new SpringSecurityToolBoundary(
                    this.delegate,
                    this.authorizationManager,
                    this.contextHolderStrategy
            );
        }
    }
}
