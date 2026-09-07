package io.github.ultramancode.springai.privacy.security.autoconfigure;

import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionEligibilityChecker;

import java.util.function.IntConsumer;

/** Lets Spring AI configure each loop without losing the supplied builder's subtype. */
final class AuthorizedToolCallingAdvisorBuilder
        extends ToolCallingAdvisor.Builder<AuthorizedToolCallingAdvisorBuilder> {

    private final ToolCallingAdvisor.Builder<?> delegate;
    private final ToolCallingManager manager;
    private final ToolAuthorizationChainAdvisor guard;
    private final IntConsumer validateOrder;

    AuthorizedToolCallingAdvisorBuilder(ToolCallingAdvisor.Builder<?> template, ToolCallingManager manager,
            ToolAuthorizationChainAdvisor guard, IntConsumer validateOrder) {
        this.delegate = template.copy().toolCallingManager(manager);
        this.manager = manager;
        this.guard = guard;
        this.validateOrder = validateOrder;
        validateOrder.accept(this.delegate.getAdvisorOrder());
    }

    @Override
    public AuthorizedToolCallingAdvisorBuilder copy() {
        return new AuthorizedToolCallingAdvisorBuilder(this.delegate, this.manager, this.guard, this.validateOrder);
    }

    @Override
    public int getAdvisorOrder() {
        return this.delegate.getAdvisorOrder();
    }

    @Override
    public AuthorizedToolCallingAdvisorBuilder toolCallingManager(ToolCallingManager manager) {
        if (manager != this.manager) {
            throw new IllegalArgumentException("The secured template's tool manager cannot be replaced");
        }
        return this;
    }

    @Override
    public AuthorizedToolCallingAdvisorBuilder advisorOrder(int order) {
        this.validateOrder.accept(order);
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
        ToolCallingAdvisor advisor = this.delegate.copy().toolCallingManager(this.manager).build();
        this.validateOrder.accept(advisor.getOrder());
        this.guard.register(advisor);
        return advisor;
    }
}
