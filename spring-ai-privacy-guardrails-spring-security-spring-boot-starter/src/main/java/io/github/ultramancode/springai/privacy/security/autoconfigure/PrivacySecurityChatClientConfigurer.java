package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyChatClientConfigurer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;
import java.util.Objects;

/**
 * Applies the starter-managed privacy advisor bundle and the request-context advisor required by
 * Spring Security tool authorization to one selected {@link ChatClient.Builder}.
 */
public final class PrivacySecurityChatClientConfigurer {

    private final PrivacyChatClientConfigurer privacyConfigurer;
    private final Advisor securityAdvisor;

    PrivacySecurityChatClientConfigurer(
            PrivacyChatClientConfigurer privacyConfigurer,
            Advisor securityAdvisor
    ) {
        this.privacyConfigurer = Objects.requireNonNull(
                privacyConfigurer,
                "privacyConfigurer must not be null"
        );
        this.securityAdvisor = Objects.requireNonNull(
                securityAdvisor,
                "securityAdvisor must not be null"
        );
    }

    /**
     * Applies the privacy advisors and security-context advisor to one selected builder.
     *
     * @param builder builder whose Spring AI tool path must be protected
     * @return the same builder
     * @throws IllegalStateException when the same builder is configured more than once
     */
    public ChatClient.Builder configure(ChatClient.Builder builder) {
        ChatClient.Builder selectedBuilder = Objects.requireNonNull(
                builder,
                "builder must not be null"
        );
        this.privacyConfigurer.configure(selectedBuilder);
        selectedBuilder.defaultAdvisors(List.of(this.securityAdvisor));
        return selectedBuilder;
    }
}
