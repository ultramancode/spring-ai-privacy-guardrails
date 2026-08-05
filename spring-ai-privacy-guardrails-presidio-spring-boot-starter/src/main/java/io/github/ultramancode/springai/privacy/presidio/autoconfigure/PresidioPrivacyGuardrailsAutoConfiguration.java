package io.github.ultramancode.springai.privacy.presidio.autoconfigure;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.presidio.PresidioAnalyzer;
import io.github.ultramancode.springai.privacy.presidio.PresidioAnalyzerConfig;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Auto-configures the Presidio analyzer provider. */
@AutoConfiguration(before = PrivacyGuardrailsAutoConfiguration.class)
@ConditionalOnProperty(prefix = "spring.ai.privacy", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PresidioPrivacyGuardrailsProperties.class)
public class PresidioPrivacyGuardrailsAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "spring.ai.privacy.presidio", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(PresidioAnalyzer.class)
    static class DefaultPresidioAnalyzerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        PresidioAnalyzerConfig presidioAnalyzerConfig(PresidioPrivacyGuardrailsProperties properties) {
            return new PresidioAnalyzerConfig(
                    properties.getAnalyzerUrl(),
                    properties.getTimeout(),
                    properties.getMaxRetries(),
                    properties.getRetryBackoff(),
                    properties.getMaxResponseBytes(),
                    properties.getHeaders()
            );
        }

        @Bean
        PresidioAnalyzer presidioAnalyzer(PresidioAnalyzerConfig config) {
            return new PresidioAnalyzer(config);
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(HealthIndicator.class)
        static class PresidioHealthConfiguration {

            @Bean
            @ConditionalOnMissingBean(name = "presidioHealthIndicator")
            HealthIndicator presidioHealthIndicator(PresidioAnalyzerConfig config) {
                return new PresidioHealthIndicator(config);
            }
        }
    }

}
