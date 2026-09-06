package io.github.ultramancode.springai.privacy.security.autoconfigure;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.function.UnaryOperator;

/** Composes the independently configured privacy and tool-authorization boundaries. */
@AutoConfiguration(
        after = ToolAuthorizationAutoConfiguration.class,
        afterName = "io.github.ultramancode.springai.privacy.autoconfigure."
                + "PrivacyGuardrailsAutoConfiguration"
)
@ConditionalOnBean(
        name = "privacyChatClientConfigurer",
        value = ToolAuthorizationChatClientConfigurer.class
)
@ConditionalOnProperty(
        prefix = "spring.ai.privacy.security",
        name = "enabled",
        havingValue = "true"
)
public class PrivacySecurityAutoConfiguration {

    @Bean
    PrivacySecurityChatClientConfigurer privacySecurityChatClientConfigurer(
            @Qualifier("privacyChatClientConfigurer")
            UnaryOperator<ChatClient.Builder> privacyChatClientConfigurer,
            ToolAuthorizationChatClientConfigurer toolAuthorizationChatClientConfigurer
    ) {
        return new PrivacySecurityChatClientConfigurer(
                privacyChatClientConfigurer,
                toolAuthorizationChatClientConfigurer
        );
    }
}
