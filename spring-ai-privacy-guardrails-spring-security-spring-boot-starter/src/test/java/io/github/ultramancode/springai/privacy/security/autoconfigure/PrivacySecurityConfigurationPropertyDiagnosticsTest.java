package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyConfigurationDiagnosticsAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class PrivacySecurityConfigurationPropertyDiagnosticsTest {

    private final ApplicationContextRunner diagnosticsRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PrivacyConfigurationDiagnosticsAutoConfiguration.class
            ));

    @Test
    void recognizesTheCanonicalSecurityProperty(CapturedOutput output) {
        this.diagnosticsRunner
                .withPropertyValues("spring.ai.privacy.security.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(output).doesNotContain(
                            "Unrecognized Spring AI Privacy Guardrails configuration property"
                    );
                });
    }

    @Test
    void suggestsTheCanonicalSecurityPropertyForFixedPathTypos(CapturedOutput output) {
        this.diagnosticsRunner
                .withPropertyValues(
                        "spring.ai.privacy.securtiy.enabled=synthetic-sensitive-value-one",
                        "spring.ai.privacy.security.enabld=synthetic-sensitive-value-two"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(output)
                            .contains("'spring.ai.privacy.securtiy.enabled'")
                            .contains("'spring.ai.privacy.security.enabld'")
                            .contains("Did you mean 'spring.ai.privacy.security.enabled'?")
                            .doesNotContain("synthetic-sensitive-value-one")
                            .doesNotContain("synthetic-sensitive-value-two");
                });
    }

    @Test
    void recognizesTheCanonicalSecurityEnvironmentName(CapturedOutput output) {
        withSystemEnvironment(Map.of(
                "SPRING_AI_PRIVACY_SECURITY_ENABLED", "false"
        )).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(output).doesNotContain(
                    "Unrecognized Spring AI Privacy Guardrails configuration property"
            );
        });
    }

    @Test
    void suggestsTheCanonicalSecurityPropertyForEnvironmentTypos(CapturedOutput output) {
        withSystemEnvironment(Map.of(
                "SPRING_AI_PRIVACY_SECURTIY_ENABLED", "synthetic-sensitive-value-one",
                "SPRING_AI_PRIVACY_SECURITY_ENABLD", "synthetic-sensitive-value-two"
        )).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(output)
                    .contains("'spring.ai.privacy.securtiy.enabled'")
                    .contains("'spring.ai.privacy.security.enabld'")
                    .contains("Did you mean 'spring.ai.privacy.security.enabled'?")
                    .doesNotContain("synthetic-sensitive-value-one")
                    .doesNotContain("synthetic-sensitive-value-two");
        });
    }

    private ApplicationContextRunner withSystemEnvironment(Map<String, Object> environment) {
        return this.diagnosticsRunner.withInitializer(context ->
                context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                environment
                        )
                )
        );
    }
}
