package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.authorization.AuthorizationManager;

/** Matches tool policies without mistaking an HTTP authorization manager for one. */
final class ToolAuthorizationPolicyCondition extends SpringBootCondition {

    private static final ResolvableType TOOL_POLICY = ResolvableType.forClassWithGenerics(
            AuthorizationManager.class, ToolAuthorizationContext.class);

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var beanFactory = context.getBeanFactory();
        if (beanFactory != null
                && BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, TOOL_POLICY, true, false).length > 0) {
            return ConditionOutcome.match("Found an AuthorizationManager<ToolAuthorizationContext> bean");
        }
        return ConditionOutcome.noMatch("No AuthorizationManager<ToolAuthorizationContext> bean");
    }
}
