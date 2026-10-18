package io.github.ultramancode.springai.privacy.client.autoconfigure;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsProperties;
import io.github.ultramancode.springai.privacy.client.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.client.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.autoconfigure.InspectionProperties;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionObserver;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationChatClientFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyEnforcementObserver;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Configures the default client policy and factory for Spring Boot. */
@AutoConfiguration(afterName = {
        "io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration",
        "io.github.ultramancode.springai.privacy.inspection.autoconfigure.InspectionAutoConfiguration",
        "io.github.ultramancode.springai.privacy.security.autoconfigure.ToolAuthorizationAutoConfiguration",
        "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration"
})
@EnableConfigurationProperties(GuardrailsProperties.class)
public class GuardrailsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @Conditional(DefaultPolicySelected.class)
    GuardrailsPolicy guardrailsPolicy(GuardrailsProperties selection, PrivacyGuardrailsProperties privacy,
            InspectionProperties inspection, ToolDisclosurePolicy disclosurePolicy) {
        GuardrailsPolicy.Builder policy = GuardrailsPolicy.builder();
        if (selection.isPrivacy()) {
            policy.privacy(settings -> {
                settings.toolDisclosurePolicy(disclosurePolicy)
                        .responseLimits(privacy.getResponseInspection().limits());
                if (privacy.getOutput().isEnabled()) {
                    settings.outputAction(privacy.getOutput().getAction())
                            .outputBlockMessage(privacy.getOutput().getBlockExceptionMessage());
                }
            });
        }
        if (selection.isInspection()) {
            policy.inspection(settings -> settings.limits(inspection.limits())
                    .applicationOutput(inspection.getOutput().isEnabled())
                    .maxOutputFrames(inspection.getOutput().getMaxFrames())
                    .outputStreamTimeout(inspection.getOutput().getStreamTimeout()));
        }
        return policy.toolAuthorization(selection.isToolAuthorization()).build();
    }

    @Bean
    @ConditionalOnMissingBean
    @SuppressWarnings("removal")
    GuardrailsChatClientFactory guardrailsChatClientFactory(
            ObjectProvider<PrivacyService> privacy,
            ObjectProvider<InspectionService> inspection,
            ObjectProvider<GuardrailsPolicy> policies,
            ObjectProvider<PrivacyEnforcementObserver> privacyObserver,
            ObjectProvider<InspectionObserver> inspectionObserver,
            ObjectProvider<GuardrailsChatClientFactory.AuthorizedClientBuilder> authorizedBuilder,
            ObjectProvider<ToolCallingAdvisor.Builder<?>> toolAdvisorBuilder,
            ObjectProvider<ObservationRegistry> registries,
            ObjectProvider<ChatClientObservationConvention> chatConvention,
            ObjectProvider<AdvisorObservationConvention> advisorConvention,
            ObjectProvider<ChatClientCustomizer> legacyCustomizers,
            ObjectProvider<ChatClientBuilderCustomizer> customizers,
            Environment environment) {
        ObservationRegistry registry = registries.getIfUnique(() -> ObservationRegistry.NOOP);
        ToolCallingAdvisor.Builder<?> toolBuilder = toolAdvisorBuilder.getIfAvailable(() ->
                ToolCallingAdvisor.builder()
                        .advisorOrder(environment.getProperty("spring.ai.chat.client.tool-calling.advisor-order",
                                Integer.class, ToolCallingAdvisor.DEFAULT_ORDER))
                        .toolCallingManager(ToolCallingManager.builder().observationRegistry(registry).build()));
        GuardrailsChatClientFactory.Builder builder = GuardrailsChatClientFactory.builder()
                .observationRegistry(registry)
                .chatClientObservationConvention(chatConvention.getIfUnique())
                .advisorObservationConvention(advisorConvention.getIfUnique())
                .toolAdvisorBuilder(toolBuilder)
                .clientCustomizer(client -> {
                    if (!environment.getProperty("spring.ai.chat.client.tool-calling.enabled", Boolean.class, true)) {
                        client.defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false));
                    }
                    legacyCustomizers.orderedStream().forEach(customizer -> customizer.customize(client));
                    customizers.orderedStream().forEach(customizer -> customizer.customize(client));
                    return client;
                });
        privacy.ifAvailable(builder::privacyService);
        inspection.ifAvailable(builder::inspectionService);
        privacyObserver.ifAvailable(builder::privacyObserver);
        inspectionObserver.ifAvailable(builder::inspectionObserver);
        authorizedBuilder.ifAvailable(builder::toolAuthorization);
        // An ambiguous default remains unavailable. Explicit policies can still be used.
        GuardrailsPolicy defaultPolicy = policies.getIfUnique();
        if (defaultPolicy != null) {
            builder.defaultPolicy(defaultPolicy);
        }
        return builder.build();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ToolAuthorizationChatClientFactory.class)
    static class ToolAuthorizationConfiguration {
        @Bean
        @ConditionalOnBean(ToolAuthorizationChatClientFactory.class)
        @ConditionalOnMissingBean(GuardrailsChatClientFactory.AuthorizedClientBuilder.class)
        GuardrailsChatClientFactory.AuthorizedClientBuilder authorizedClientBuilder(
                ToolAuthorizationChatClientFactory factory) {
            return factory::builder;
        }
    }

    static final class DefaultPolicySelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            return environment.getProperty("spring.ai.guardrails.privacy", Boolean.class, false)
                    || environment.getProperty("spring.ai.guardrails.inspection", Boolean.class, false)
                    || environment.getProperty("spring.ai.guardrails.tool-authorization", Boolean.class, false);
        }
    }
}
