package io.github.ultramancode.springai.privacy.client.autoconfigure;

import io.github.ultramancode.springai.privacy.client.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.client.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionObserver;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationChatClientFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyEnforcementObserver;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** Creates a client factory from application services and an optional default policy bean. */
@AutoConfiguration(afterName = {
        "io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration",
        "io.github.ultramancode.springai.privacy.inspection.autoconfigure.InspectionAutoConfiguration",
        "io.github.ultramancode.springai.privacy.security.autoconfigure.ToolAuthorizationAutoConfiguration",
        "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration"
})
public class GuardrailsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
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
                    customizers.orderedStream().forEach(customizer -> customizer.customize(client));
                    return client;
                });
        privacy.ifAvailable(builder::privacyService);
        inspection.ifAvailable(builder::inspectionService);
        privacyObserver.ifAvailable(builder::privacyObserver);
        inspectionObserver.ifAvailable(builder::inspectionObserver);
        authorizedBuilder.ifAvailable(builder::toolAuthorization);
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
}
