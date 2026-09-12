package io.github.ultramancode.springai.privacy.security.autoconfigure;

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;
import org.springframework.core.PriorityOrdered;

import java.util.function.IntConsumer;

/**
 * Configures tool advisors created by Spring AI's automatic registration with
 * the authorization-aware {@link ToolCallingManager}. Preserves the supplied
 * builder's advisor subtype. Builder copies retain checks against replacing
 * the tool-calling manager or using an incompatible advisor order.
 */
final class AuthorizedToolCallingAdvisorBuilder
        extends ToolCallingAdvisor.Builder<AuthorizedToolCallingAdvisorBuilder> {

    private final ToolCallingAdvisor.Builder<?> delegate;
    private final ToolCallingManager toolCallingManager;
    private final ToolAuthorizationChainAdvisor chainValidator;
    private final IntConsumer orderValidator;

    AuthorizedToolCallingAdvisorBuilder(ToolCallingAdvisor.Builder<?> toolAdvisorBuilder, ToolCallingManager toolCallingManager,
            ToolAuthorizationChainAdvisor chainValidator, IntConsumer orderValidator) {
        this.delegate = toolAdvisorBuilder.copy().toolCallingManager(toolCallingManager);
        this.toolCallingManager = toolCallingManager;
        this.chainValidator = chainValidator;
        this.orderValidator = orderValidator;
        orderValidator.accept(this.delegate.getAdvisorOrder());
    }

    @Override
    public AuthorizedToolCallingAdvisorBuilder copy() {
        return new AuthorizedToolCallingAdvisorBuilder(
                this.delegate, this.toolCallingManager, this.chainValidator, this.orderValidator);
    }

    @Override
    public int getAdvisorOrder() {
        return this.delegate.getAdvisorOrder();
    }

    @Override
    public AuthorizedToolCallingAdvisorBuilder toolCallingManager(ToolCallingManager toolCallingManager) {
        if (toolCallingManager != this.toolCallingManager) {
            throw new IllegalArgumentException(
                    "The authorized tool advisor builder's ToolCallingManager cannot be replaced");
        }
        return this;
    }

    @Override
    public AuthorizedToolCallingAdvisorBuilder advisorOrder(int order) {
        this.orderValidator.accept(order);
        this.delegate.advisorOrder(order);
        return this;
    }

    @Override
    public AuthorizedToolCallingAdvisorBuilder toolExecutionEligibilityChecker(ToolExecutionEligibilityChecker checker) {
        this.delegate.toolExecutionEligibilityChecker(checker);
        return this;
    }

    @Override
    public AuthorizedToolCallingAdvisorBuilder conversationHistoryEnabled(boolean enabled) {
        this.delegate.conversationHistoryEnabled(enabled);
        return this;
    }

    @Override
    public AuthorizedToolCallingAdvisorBuilder disableInternalConversationHistory() {
        return conversationHistoryEnabled(false);
    }

    @Override
    public ToolCallingAdvisor build() {
        ToolCallingAdvisor advisor = this.delegate.copy().toolCallingManager(this.toolCallingManager).build();
        // PriorityOrdered tool advisors run before the ordinary Ordered authorization lifecycle,
        // regardless of their numeric order values.
        if (advisor instanceof PriorityOrdered) {
            throw new IllegalArgumentException(
                    "PriorityOrdered tool advisors are incompatible with tool authorization; "
                            + "use standard advisor ordering so the authorization lifecycle runs before the tool loop");
        }
        this.orderValidator.accept(advisor.getOrder());
        this.chainValidator.register(advisor);
        return advisor;
    }
}
