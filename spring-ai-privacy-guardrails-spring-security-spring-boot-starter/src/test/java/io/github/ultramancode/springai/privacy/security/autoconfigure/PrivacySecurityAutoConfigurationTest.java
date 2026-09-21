package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.security.PrivacySecurityChatClientFactory;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationChatClientFactory;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PrivacySecurityAutoConfigurationTest {

    private final PiiAnalyzer emptyAnalyzer = (text, options) -> List.of();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PrivacyGuardrailsAutoConfiguration.class,
                    ToolAuthorizationAutoConfiguration.class,
                    PrivacySecurityAutoConfiguration.class,
                    ToolCallingAutoConfiguration.class,
                    ChatClientAutoConfiguration.class
            ))
            .withUserConfiguration(TestPolicyConfiguration.class)
            .withBean(PiiAnalyzer.class, () -> this.emptyAnalyzer)
            .withBean(ChatModel.class, () -> mock(ChatModel.class));

    @Test
    void createsIndependentBuildersForTheSameModel() {
        this.contextRunner.run(context -> {
            PrivacySecurityChatClientFactory factory = context.getBean(PrivacySecurityChatClientFactory.class);
            ChatModel model = context.getBean(ChatModel.class);
            assertThat(factory.builder(model)).isNotSameAs(factory.builder(model));
        });
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void preparesOnlyTheBoundariesWhoseDependenciesExist(boolean analyzerPresent, boolean policyPresent) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PrivacyGuardrailsAutoConfiguration.class,
                        ToolAuthorizationAutoConfiguration.class,
                        PrivacySecurityAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class,
                        ChatClientAutoConfiguration.class))
                .withBean(ChatModel.class, () -> mock(ChatModel.class));
        if (analyzerPresent) {
            PiiAnalyzer analyzer = (text, options) -> List.of();
            runner = runner.withBean(PiiAnalyzer.class, () -> analyzer);
        }
        if (policyPresent) {
            runner = runner.withUserConfiguration(TestPolicyConfiguration.class);
        }
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ChatClient.Builder.class);
            assertThat(context.getBeansOfType(PrivacyChatClientConfigurer.class)).hasSize(analyzerPresent ? 1 : 0);
            assertThat(context.getBeansOfType(ToolAuthorizationChatClientFactory.class)).hasSize(policyPresent ? 1 : 0);
            assertThat(context.getBeansOfType(PrivacySecurityChatClientFactory.class))
                    .hasSize(analyzerPresent && policyPresent ? 1 : 0);
        });
    }

    @Test
    void remainsInactiveWhenPrivacyClassesAreAbsent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PrivacySecurityAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(
                        "io.github.ultramancode.springai.privacy.autoconfigure"
                ))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PrivacySecurityChatClientFactory.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestPolicyConfiguration {

        @Bean
        AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager() {
            return (authentication, context) -> new AuthorizationDecision(true);
        }
    }
}
