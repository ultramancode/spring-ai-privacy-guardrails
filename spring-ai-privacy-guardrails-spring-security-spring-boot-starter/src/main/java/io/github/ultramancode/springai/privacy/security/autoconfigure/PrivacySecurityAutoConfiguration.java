package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.security.PrivacySecurityChatClientFactory;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationChatClientFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;

import java.util.function.IntFunction;

/** Composes the independently configured privacy and tool-authorization boundaries. */
@AutoConfiguration(
        after = ToolAuthorizationAutoConfiguration.class,
        afterName = "io.github.ultramancode.springai.privacy.autoconfigure."
                + "PrivacyGuardrailsAutoConfiguration"
)
@ConditionalOnBean(
        name = "privacyChatClientConfigurer",
        value = ToolAuthorizationChatClientFactory.class
)
public class PrivacySecurityAutoConfiguration {

    @Bean
    PrivacySecurityChatClientFactory privacySecurityChatClientFactory(
            @Qualifier("privacyChatClientConfigurer")
            IntFunction<ModelRequestBoundaryConfigurer> privacyChatClientConfigurer,
            ToolAuthorizationChatClientFactory toolAuthorizationChatClientFactory
    ) {
        return new PrivacySecurityChatClientFactory(
                privacyChatClientConfigurer,
                toolAuthorizationChatClientFactory
        );
    }
}
