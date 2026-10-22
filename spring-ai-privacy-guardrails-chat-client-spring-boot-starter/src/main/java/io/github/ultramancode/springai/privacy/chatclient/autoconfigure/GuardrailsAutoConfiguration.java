package io.github.ultramancode.springai.privacy.chatclient.autoconfigure;

import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionObserver;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationChatClientFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyEnforcementObserver;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.core.env.Environment;

/** Configures factory builders and a default factory from application services and client defaults. */
@AutoConfiguration(afterName = {
    "io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration",
    "io.github.ultramancode.springai.privacy.inspection.autoconfigure.InspectionAutoConfiguration",
    "io.github.ultramancode.springai.privacy.security.autoconfigure.ToolAuthorizationAutoConfiguration",
    "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration",
    "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration"
})
public class GuardrailsAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    GuardrailsChatClientFactory guardrailsChatClientFactory(GuardrailsChatClientFactory.Builder builder) {
        return builder.build();
    }

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @ConditionalOnMissingBean
    @SuppressWarnings("removal")
    GuardrailsChatClientFactory.Builder guardrailsChatClientFactoryBuilder(
        ObjectProvider<PrivacyService> privacyServiceProvider,
        ObjectProvider<InspectionService> inspectionServiceProvider,
        ObjectProvider<GuardrailsPolicy> guardrailsPolicyProvider,
        ObjectProvider<PrivacyEnforcementObserver> privacyObserverProvider,
        ObjectProvider<InspectionObserver> inspectionObserverProvider,
        ObjectProvider<GuardrailsChatClientFactory.ToolAuthorizationConfigurer> toolAuthorizationConfigurerProvider,
        ObjectProvider<ToolCallingAdvisor.Builder<?>> toolAdvisorBuilderProvider,
        ObjectProvider<ToolCallingManager> toolCallingManagerProvider,
        ObjectProvider<ObservationRegistry> observationRegistryProvider,
        ObjectProvider<ChatClientObservationConvention> chatClientObservationConventionProvider,
        ObjectProvider<AdvisorObservationConvention> advisorObservationConventionProvider,
        ObjectProvider<ChatClientCustomizer> chatClientCustomizers,
        ObjectProvider<ChatClientBuilderCustomizer> chatClientBuilderCustomizers,
        Environment environment) {
        ObservationRegistry observationRegistry = observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP);
        ToolCallingAdvisor.Builder<?> toolAdvisorBuilder = toolAdvisorBuilderProvider.getIfAvailable(() ->
            ToolCallingAdvisor.builder()
                .advisorOrder(environment.getProperty("spring.ai.chat.client.tool-calling.advisor-order",
                    Integer.class, ToolCallingAdvisor.DEFAULT_ORDER))
                .toolCallingManager(toolCallingManagerProvider.getIfAvailable(() -> ToolCallingManager.builder()
                    .observationRegistry(observationRegistry)
                    .build())));
        GuardrailsChatClientFactory.Builder guardrailsFactoryBuilder = GuardrailsChatClientFactory.builder()
            .observationRegistry(observationRegistry)
            .chatClientObservationConvention(chatClientObservationConventionProvider.getIfUnique())
            .advisorObservationConvention(advisorObservationConventionProvider.getIfUnique())
            .toolAdvisorBuilder(toolAdvisorBuilder)
            .clientCustomizer(chatClientBuilder -> {
                if (!environment.getProperty("spring.ai.chat.client.tool-calling.enabled", Boolean.class, true)) {
                    chatClientBuilder.defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false));
                }
                chatClientCustomizers.orderedStream()
                    .forEach(chatClientCustomizer -> chatClientCustomizer.customize(chatClientBuilder));
                chatClientBuilderCustomizers.orderedStream()
                    .forEach(chatClientBuilderCustomizer -> chatClientBuilderCustomizer.customize(chatClientBuilder));
                return chatClientBuilder;
            });
        privacyServiceProvider.ifAvailable(guardrailsFactoryBuilder::privacyService);
        InspectionService inspectionService = inspectionServiceProvider.getIfUnique();
        if (inspectionService != null) {
            guardrailsFactoryBuilder
                .inputInspectionService(inspectionService)
                .outputInspectionService(inspectionService);
        }
        privacyObserverProvider.ifAvailable(guardrailsFactoryBuilder::privacyObserver);
        inspectionObserverProvider.ifAvailable(guardrailsFactoryBuilder::inspectionObserver);
        toolAuthorizationConfigurerProvider.ifAvailable(guardrailsFactoryBuilder::toolAuthorization);
        GuardrailsPolicy defaultPolicy = guardrailsPolicyProvider.getIfUnique();
        if (defaultPolicy != null) {
            guardrailsFactoryBuilder.defaultPolicy(defaultPolicy);
        }
        return guardrailsFactoryBuilder;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ToolAuthorizationChatClientFactory.class)
    static class SpringSecurityConfiguration {
        @Bean
        @ConditionalOnBean(ToolAuthorizationChatClientFactory.class)
        @ConditionalOnMissingBean(GuardrailsChatClientFactory.ToolAuthorizationConfigurer.class)
        GuardrailsChatClientFactory.ToolAuthorizationConfigurer toolAuthorizationConfigurer(
            ToolAuthorizationChatClientFactory toolAuthorizationChatClientFactory) {
            return toolAuthorizationChatClientFactory::prepareToolAuthorization;
        }
    }
}
