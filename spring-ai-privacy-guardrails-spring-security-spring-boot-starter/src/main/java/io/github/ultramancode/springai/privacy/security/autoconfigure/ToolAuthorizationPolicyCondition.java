package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import org.springframework.aop.scope.ScopedProxyUtils;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.authorization.AuthorizationManager;

/**
 * Matches when an {@code AuthorizationManager<ToolAuthorizationContext>} bean is eligible for injection.
 */
final class ToolAuthorizationPolicyCondition extends SpringBootCondition {

    private static final ResolvableType TOOL_POLICY_TYPE = ResolvableType.forClassWithGenerics(
            AuthorizationManager.class, ToolAuthorizationContext.class);

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        if (beanFactory != null) {
            for (String beanName : BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
                    beanFactory, TOOL_POLICY_TYPE, true, false)) {
                if (isPolicyCandidate(beanFactory, beanName)) {
                    return ConditionOutcome.match(
                            "Found an eligible AuthorizationManager<ToolAuthorizationContext> bean");
                }
            }
        }
        return ConditionOutcome.noMatch("No eligible AuthorizationManager<ToolAuthorizationContext> bean");
    }

    private static boolean isPolicyCandidate(ConfigurableListableBeanFactory beanFactory, String beanName) {
        if (beanFactory.containsBeanDefinition(beanName)) {
            BeanDefinition definition = beanFactory.getMergedBeanDefinition(beanName);
            if (isInjectionCandidate(definition)) {
                return true;
            }
            // Scoped targets are excluded from injection; the proxy's flags determine eligibility.
            if (ScopedProxyUtils.isScopedTarget(beanName)) {
                String proxyBeanName = ScopedProxyUtils.getOriginalBeanName(beanName);
                return beanFactory.containsBeanDefinition(proxyBeanName)
                        && isInjectionCandidate(beanFactory.getMergedBeanDefinition(proxyBeanName));
            }
            return false;
        }
        BeanFactory parent = beanFactory.getParentBeanFactory();
        if (!beanFactory.containsLocalBean(beanName)
                && parent instanceof ConfigurableListableBeanFactory parentBeanFactory) {
            return isPolicyCandidate(parentBeanFactory, beanName);
        }
        // The type lookup already found this bean. Keep candidates without an inspectable definition,
        // such as directly registered singletons.
        return true;
    }

    private static boolean isInjectionCandidate(BeanDefinition definition) {
        if (!definition.isAutowireCandidate()) {
            return false;
        }
        if (definition instanceof AbstractBeanDefinition abstractDefinition) {
            return abstractDefinition.isDefaultCandidate();
        }
        return true;
    }
}
