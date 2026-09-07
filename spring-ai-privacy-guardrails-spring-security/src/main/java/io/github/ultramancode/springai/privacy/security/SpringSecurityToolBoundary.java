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
 * Provides request-scoped authorization for Spring AI tools. For selected ChatClients,
 * install the lifecycle and definition advisors together with a {@link ToolCallingAdvisor}
 * using this boundary's manager. The shared {@link ChatModel} keeps its original manager.
 */
public final class SpringSecurityToolBoundary {

    private final ToolAuthorizationSessionRegistry sessionRegistry;
    private final ToolCallingManager toolCallingManager;
    private final Advisor toolAuthorizationLifecycleAdvisor;
    private final Advisor toolDefinitionAuthorizationAdvisor;

    private SpringSecurityToolBoundary(
            ToolCallingManager delegate,
            AuthorizationManager<ToolAuthorizationContext> authorizationManager,
            SecurityContextHolderStrategy contextHolderStrategy
    ) {
        this.sessionRegistry = new ToolAuthorizationSessionRegistry();
        this.toolCallingManager = new AuthorizationAwareToolCallingManager(
                authorizationManager,
                this.sessionRegistry,
                delegate
        );
        this.toolAuthorizationLifecycleAdvisor = new ToolAuthorizationLifecycleAdvisor(
                this.sessionRegistry,
                contextHolderStrategy
        );
        this.toolDefinitionAuthorizationAdvisor = new ToolDefinitionAuthorizationAdvisor(
                this.toolCallingManager
        );
    }

    /**
     * Creates a builder that decorates an existing Spring AI tool-calling manager.
     *
     * <p>The resulting boundary filters tool definitions and reauthorizes tool calls
     * before execution. The delegate must execute tool calls using the callbacks
     * supplied in the prompt.</p>
     *
     * @param delegate manager to decorate and use for tool-call execution
     * @param authorizationManager policy evaluated for definition exposure and tool execution
     * @return boundary builder
     */
    public static Builder builder(
            ToolCallingManager delegate,
            AuthorizationManager<ToolAuthorizationContext> authorizationManager
    ) {
        return new Builder(delegate, authorizationManager);
    }

    /**
     * Returns the manager that filters definitions and reauthorizes tool execution.
     * Use it in the tool-calling advisor, including {@code ToolSearchToolCallingAdvisor}
     * where it also filters definitions before indexing. A ChatModel may keep its
     * original manager when {@link #toolDefinitionAuthorizationAdvisor()} is installed.
     * Alternatively, install this manager directly in the model and tool-calling
     * advisor together with {@link #toolAuthorizationAdvisor()}.
     *
     * @return authorization-aware tool-calling manager
     */
    public ToolCallingManager toolCallingManager() {
        return this.toolCallingManager;
    }

    /**
     * Returns the advisor that captures the current authentication for tool authorization
     * in blocking and streaming requests.
     *
     * @return request-scoped tool-authorization lifecycle advisor
     */
    public Advisor toolAuthorizationAdvisor() {
        return this.toolAuthorizationLifecycleAdvisor;
    }

    /**
     * Returns the advisor that filters tool callbacks before each model call.
     * Use it with the lifecycle advisor and a tool-calling advisor configured with
     * this boundary's manager when the model keeps its original manager.
     * When privacy advisors are also used, register this advisor after the privacy
     * model boundary so callback snapshot validation precedes definition filtering.
     * Advisors that mutate tools must run before this advisor.
     *
     * @return model-facing tool definition authorization advisor
     */
    public Advisor toolDefinitionAuthorizationAdvisor() {
        return this.toolDefinitionAuthorizationAdvisor;
    }

    int activeSessionCount() {
        return this.sessionRegistry.activeSessionCount();
    }

    /** Builder for a Spring Security tool boundary. */
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
         * {@code Authentication} for blocking calls. For streaming calls, the strategy
         * supplies a fallback {@code Authentication} that is used only when the Reactor
         * context has no {@code SecurityContext} entry.
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
         * Builds the authorization manager and advisors that share request state.
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
