package io.github.ultramancode.springai.privacy.security;

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

import java.util.Objects;

/**
 * Provides a manager and advisor that share one request-scoped tool authorization registry.
 * Applications must install both components to complete the boundary.
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
     * <p>This boundary handles tool-definition resolution and validates requested tool
     * calls before invoking the delegate. The delegate must execute tool calls using the
     * callbacks supplied in the prompt.</p>
     *
     * @param delegate manager used for tool-call execution
     * @param authorizationManager policy evaluated for definition and execution phases
     * @return boundary builder
     */
    public static Builder builder(
            ToolCallingManager delegate,
            AuthorizationManager<ToolAuthorizationContext> authorizationManager
    ) {
        return new Builder(delegate, authorizationManager);
    }

    /**
     * Returns the manager that must be shared by the {@link ChatModel} and
     * {@link ToolCallingAdvisor}.
     *
     * @return authorization-aware tool-calling manager
     */
    public ToolCallingManager toolCallingManager() {
        return this.toolCallingManager;
    }

    /**
     * Returns the advisor that captures request authentication for blocking and
     * reactive calls.
     *
     * @return request authentication advisor
     */
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

        /**
         * Sets the {@link SecurityContextHolderStrategy} used to obtain request
         * {@code Authentication} for blocking calls. For streaming calls, it supplies
         * the thread-local fallback used only when the Reactor context has no
         * {@code SecurityContext} entry.
         *
         * @param contextHolderStrategy application-selected context holder strategy
         * @return this builder
         */
        public Builder securityContextHolderStrategy(
                SecurityContextHolderStrategy contextHolderStrategy
        ) {
            this.contextHolderStrategy = Objects.requireNonNull(
                    contextHolderStrategy,
                    "contextHolderStrategy must not be null"
            );
            return this;
        }

        /**
         * Builds one manager/advisor pair backed by a private request registry.
         *
         * @return complete Spring Security tool boundary
         */
        public SpringSecurityToolBoundary build() {
            return new SpringSecurityToolBoundary(
                    this.delegate,
                    this.authorizationManager,
                    this.contextHolderStrategy
            );
        }
    }
}
