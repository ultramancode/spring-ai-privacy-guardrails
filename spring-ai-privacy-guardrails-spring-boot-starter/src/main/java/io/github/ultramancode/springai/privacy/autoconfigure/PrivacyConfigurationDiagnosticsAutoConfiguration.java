package io.github.ultramancode.springai.privacy.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

/** Auto-configures non-blocking diagnostics for likely privacy property typos. */
@AutoConfiguration(before = PrivacyGuardrailsAutoConfiguration.class)
public class PrivacyConfigurationDiagnosticsAutoConfiguration {

    @Bean
    @Lazy(false)
    @ConditionalOnMissingBean(name = "privacyConfigurationPropertyDiagnostics")
    PrivacyConfigurationPropertyDiagnostics privacyConfigurationPropertyDiagnostics(
            Environment environment
    ) {
        return new PrivacyConfigurationPropertyDiagnostics(environment);
    }

}
