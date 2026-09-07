package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import org.junit.jupiter.api.Test;
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

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PrivacyGuardrailsAutoConfiguration.class,
                    ToolAuthorizationAutoConfiguration.class,
                    PrivacySecurityAutoConfiguration.class,
                    ToolCallingAutoConfiguration.class,
                    ChatClientAutoConfiguration.class
            ))
            .withUserConfiguration(TestPolicyConfiguration.class)
            .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of())
            .withBean(ChatModel.class, () -> mock(ChatModel.class))
            .withPropertyValues(
                    "spring.ai.privacy.enabled=true",
                    "spring.ai.privacy.security.enabled=true"
            );

    @Test
    void contributesTheCombinedConfigurerWhenBothBoundariesAreAvailable() {
        this.contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PrivacyChatClientConfigurer.class);
            assertThat(context).hasSingleBean(ToolAuthorizationChatClientFactory.class);
            assertThat(context).hasSingleBean(PrivacySecurityChatClientFactory.class);
        });
    }

    @Test
    void createsIndependentBuildersWithBothBoundaries() {
        this.contextRunner.run(context -> {
            PrivacySecurityChatClientFactory factory = context.getBean(PrivacySecurityChatClientFactory.class);
            ChatModel model = context.getBean(ChatModel.class);
            assertThat(factory.builder(model)).isNotSameAs(factory.builder(model));
        });
    }

    @Test
    void keepsToolAuthorizationActiveWhenPrivacyIsDisabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PrivacyGuardrailsAutoConfiguration.class,
                        ToolAuthorizationAutoConfiguration.class,
                        PrivacySecurityAutoConfiguration.class,
                        ToolCallingAutoConfiguration.class,
                        ChatClientAutoConfiguration.class
                ))
                .withUserConfiguration(TestPolicyConfiguration.class)
                .withBean(ChatModel.class, () -> mock(ChatModel.class))
                .withPropertyValues("spring.ai.privacy.security.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PrivacyChatClientConfigurer.class);
                    assertThat(context).hasSingleBean(ToolAuthorizationChatClientFactory.class);
                    assertThat(context).doesNotHaveBean(PrivacySecurityChatClientFactory.class);
                });
    }

    @Test
    void remainsInactiveWhenPrivacyClassesAreAbsent() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PrivacySecurityAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(
                        "io.github.ultramancode.springai.privacy.autoconfigure"
                ))
                .withPropertyValues("spring.ai.privacy.security.enabled=true")
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
