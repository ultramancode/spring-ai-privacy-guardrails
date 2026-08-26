package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.security.SpringSecurityToolBoundary;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authorization.AuthorizationManager;

import java.util.Arrays;
import java.util.List;

/** Auto-configures an authorization-aware manager shared by Spring AI model and advisor paths. */
@AutoConfiguration(
        after = PrivacyGuardrailsAutoConfiguration.class,
        afterName = "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration"
)
@ConditionalOnClass({AuthorizationManager.class, ToolCallingManager.class})
@ConditionalOnProperty(
        prefix = "spring.ai.privacy.security",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(PrivacySecurityProperties.class)
public class PrivacySecurityAutoConfiguration {

    private static final String SECURED_MANAGER_BEAN_NAME =
            "privacySecurityToolCallingManager";

    @Bean
    @ConditionalOnMissingBean
    SpringSecurityToolBoundary springSecurityToolBoundary(
            AuthorizationManager<ToolAuthorizationContext> authorizationManager,
            ListableBeanFactory beanFactory
    ) {
        List<ToolCallingManager> defaultManagers = Arrays.stream(
                        beanFactory.getBeanNamesForType(ToolCallingManager.class, false, false)
                )
                .filter(name -> !SECURED_MANAGER_BEAN_NAME.equals(name))
                .map(name -> beanFactory.getBean(name, ToolCallingManager.class))
                .filter(DefaultToolCallingManager.class::isInstance)
                .toList();
        if (defaultManagers.isEmpty()) {
            throw new IllegalStateException(
                    "Privacy Security requires Spring AI's auto-configured "
                            + "ToolCallingManager and does not create a fallback manager"
            );
        }
        if (defaultManagers.size() > 1) {
            throw new IllegalStateException(
                    "Privacy Security found multiple Spring AI DefaultToolCallingManager "
                            + "candidates; provide an explicit SpringSecurityToolBoundary"
            );
        }
        return SpringSecurityToolBoundary.builder(
                defaultManagers.get(0),
                authorizationManager
        ).build();
    }

    @Bean(SECURED_MANAGER_BEAN_NAME)
    @Primary
    ToolCallingManager privacySecurityToolCallingManager(
            SpringSecurityToolBoundary boundary
    ) {
        return boundary.toolCallingManager();
    }

    @Bean
    PrivacySecurityChatClientConfigurer privacySecurityChatClientConfigurer(
            PrivacyChatClientConfigurer privacyConfigurer,
            SpringSecurityToolBoundary boundary
    ) {
        return new PrivacySecurityChatClientConfigurer(
                privacyConfigurer,
                boundary.advisor()
        );
    }

    @Bean
    SmartInitializingSingleton privacySecurityToolCallingManagerVerifier(
            ObjectProvider<ToolCallingManager> managers,
            SpringSecurityToolBoundary boundary
    ) {
        return () -> {
            ToolCallingManager selected = managers.getIfUnique();
            if (selected != boundary.toolCallingManager()) {
                throw new IllegalStateException(
                        "Privacy Security requires its decorated ToolCallingManager "
                                + "to be the primary application manager"
                );
            }
        };
    }
}
