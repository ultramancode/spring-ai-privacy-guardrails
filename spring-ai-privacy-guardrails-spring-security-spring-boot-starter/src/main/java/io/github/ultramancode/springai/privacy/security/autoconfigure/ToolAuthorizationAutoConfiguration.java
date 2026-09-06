package io.github.ultramancode.springai.privacy.security.autoconfigure;

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
        afterName = "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration"
)
@ConditionalOnClass({AuthorizationManager.class, ToolCallingManager.class})
@ConditionalOnProperty(
        prefix = "spring.ai.privacy.security",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(PrivacySecurityProperties.class)
public class ToolAuthorizationAutoConfiguration {

    private static final String AUTHORIZATION_AWARE_MANAGER_BEAN_NAME =
            "authorizationAwareToolCallingManager";

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

    @Bean(AUTHORIZATION_AWARE_MANAGER_BEAN_NAME)
    @Primary
    ToolCallingManager authorizationAwareToolCallingManager(
            SpringSecurityToolBoundary boundary
    ) {
        return boundary.toolCallingManager();
    }

    @Bean
    ToolAuthorizationChatClientConfigurer toolAuthorizationChatClientConfigurer(
            SpringSecurityToolBoundary boundary
    ) {
        return new ToolAuthorizationChatClientConfigurer(
                boundary.toolAuthorizationAdvisor()
        );
    }

    @Bean
    SmartInitializingSingleton authorizationAwareToolCallingManagerVerifier(
            ObjectProvider<ToolCallingManager> toolCallingManagers,
            SpringSecurityToolBoundary boundary
    ) {
        return () -> {
            ToolCallingManager selected = toolCallingManagers.getIfUnique();
            if (selected != boundary.toolCallingManager()) {
                throw new IllegalStateException(
                        "Tool authorization requires its decorated ToolCallingManager "
                                + "to be the primary application manager"
                );
            }
        };
    }

    private static ToolCallingManager resolveDefaultToolCallingManager(ListableBeanFactory beanFactory) {
        List<ToolCallingManager> defaultManagerCandidates = Arrays.stream(
                        beanFactory.getBeanNamesForType(ToolCallingManager.class, false, false)
                )
                .filter(name -> !AUTHORIZATION_AWARE_MANAGER_BEAN_NAME.equals(name))
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
