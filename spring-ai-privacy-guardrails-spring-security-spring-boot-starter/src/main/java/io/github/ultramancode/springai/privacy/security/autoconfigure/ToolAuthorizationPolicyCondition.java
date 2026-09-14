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
 * Matches when an {@code AuthorizationManager<ToolAuthorizationContext>} policy is an injection candidate,
 * either directly or through a scoped proxy.
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
        if (beanFactory.containsLocalBean(beanName)) {
            return isPolicyCandidateInCurrentFactory(beanFactory, beanName);
        }
        BeanFactory parent = beanFactory.getParentBeanFactory();
        if (parent instanceof ConfigurableListableBeanFactory parentBeanFactory) {
            return isPolicyCandidate(parentBeanFactory, beanName);
        }
        // The parent does not expose bean definitions for this type-matched policy.
        // Keep it as a candidate, consistent with Spring's autowiring behavior.
        return true;
    }

    private static boolean isPolicyCandidateInCurrentFactory(
            ConfigurableListableBeanFactory beanFactory, String beanName) {
        if (!beanFactory.containsBeanDefinition(beanName)) {
            // A singleton registered in the current factory can exist without a BeanDefinition to inspect.
            return true;
        }
        BeanDefinition mergedDefinition = beanFactory.getMergedBeanDefinition(beanName);
        if (isInjectionCandidate(mergedDefinition)) {
            return true;
        }
        if (!ScopedProxyUtils.isScopedTarget(beanName)) {
            return false;
        }
        // Spring excludes scoped targets from autowiring, so check the proxy's candidate settings.
        // Example: beanName = "scopedTarget.toolAuthorizationManager" (target)
        //          proxyBeanName = "toolAuthorizationManager" (proxy)
        String proxyBeanName = ScopedProxyUtils.getOriginalBeanName(beanName);
        return beanFactory.containsBeanDefinition(proxyBeanName)
                && isInjectionCandidate(beanFactory.getMergedBeanDefinition(proxyBeanName));
    }

    /** Checks whether the definition allows injection by type without a qualifier. */
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
