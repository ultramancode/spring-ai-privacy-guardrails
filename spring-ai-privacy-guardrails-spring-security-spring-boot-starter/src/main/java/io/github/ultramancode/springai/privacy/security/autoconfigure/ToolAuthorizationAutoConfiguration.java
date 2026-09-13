package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.security.SpringSecurityToolBoundary;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientCustomizer;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.core.env.Environment;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authorization.AuthorizationManager;

import java.util.Arrays;
import java.util.List;

/**
 * Prepares tool authorization for explicitly configured ChatClients without
 * replacing the application's existing {@link ToolCallingManager}.
 */
@AutoConfiguration(
        afterName = {
                "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration",
                "org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration"
        }
)
@ConditionalOnClass({AuthorizationManager.class, ToolCallingManager.class})
@ConditionalOnProperty(
        prefix = "spring.ai.privacy.security",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(PrivacySecurityProperties.class)
public class ToolAuthorizationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    SpringSecurityToolBoundary springSecurityToolBoundary(
            AuthorizationManager<ToolAuthorizationContext> authorizationManager,
            ListableBeanFactory beanFactory
    ) {
        return SpringSecurityToolBoundary.builder(
                resolveDefaultToolCallingManager(beanFactory),
                authorizationManager
        ).build();
    }

    @Bean
    // Apply Spring AI 2.0's deprecated ChatClientCustomizer before ChatClientBuilderCustomizer
    // to preserve existing application customizations and Spring AI's application order.
    @SuppressWarnings("removal")
    ToolAuthorizationChatClientFactory toolAuthorizationChatClientFactory(
            SpringSecurityToolBoundary boundary,
            ObjectProvider<ToolCallingAdvisor.Builder<?>> toolCallingAdvisorBuilder,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<ChatClientObservationConvention> chatClientObservationConvention,
            ObjectProvider<AdvisorObservationConvention> advisorObservationConvention,
            ObjectProvider<ChatClientCustomizer> legacyCustomizers,
            ObjectProvider<ChatClientBuilderCustomizer> customizers,
            Environment environment
    ) {
        ToolCallingAdvisor.Builder<?> defaultToolAdvisorBuilder = toolCallingAdvisorBuilder.getIfAvailable(() -> {
            int toolOrder = environment.getProperty(
                    "spring.ai.chat.client.tool-calling.advisor-order", Integer.class, ToolCallingAdvisor.DEFAULT_ORDER);
            return ToolCallingAdvisor.builder().advisorOrder(toolOrder);
        });
        return new ToolAuthorizationChatClientFactory(boundary, defaultToolAdvisorBuilder,
                observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP),
                chatClientObservationConvention.getIfUnique(), advisorObservationConvention.getIfUnique(),
                builder -> {
                    legacyCustomizers.orderedStream().forEach(customizer -> customizer.customize(builder));
                    customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
                    return builder;
                }, environment.getProperty("spring.ai.chat.client.tool-calling.enabled", Boolean.class, true));
    }

    private static ToolCallingManager resolveDefaultToolCallingManager(ListableBeanFactory beanFactory) {
        List<ToolCallingManager> defaultManagerCandidates = Arrays.stream(
                        beanFactory.getBeanNamesForType(ToolCallingManager.class, false, false)
                )
                .map(name -> beanFactory.getBean(name, ToolCallingManager.class))
                .filter(DefaultToolCallingManager.class::isInstance)
                .toList();
        if (defaultManagerCandidates.isEmpty()) {
            throw new IllegalStateException(
                    "Tool authorization requires Spring AI's auto-configured "
                            + "ToolCallingManager and does not create a fallback manager"
            );
        }
        if (defaultManagerCandidates.size() > 1) {
            throw new IllegalStateException(
                    "Tool authorization found multiple Spring AI DefaultToolCallingManager "
                            + "candidates. Provide an explicit SpringSecurityToolBoundary"
            );
        }
        return defaultManagerCandidates.get(0);
    }
}
