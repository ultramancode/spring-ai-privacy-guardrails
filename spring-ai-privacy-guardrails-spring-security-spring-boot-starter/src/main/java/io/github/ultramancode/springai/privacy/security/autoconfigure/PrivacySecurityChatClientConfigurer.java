package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyChatClientConfigurer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;

/** Applies the complete privacy bundle plus its Spring Security context boundary. */
public final class PrivacySecurityChatClientConfigurer {

    private final PrivacyChatClientConfigurer privacyConfigurer;
    private final Advisor securityAdvisor;
    private final WeakHashMap<ChatClient.Builder, Boolean> configuredBuilders = new WeakHashMap<>();

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
     * Applies the privacy and authorization advisors to one selected builder.
     *
     * @param builder builder whose Spring AI tool path must be protected
     * @return the same builder
     */
    public ChatClient.Builder configure(ChatClient.Builder builder) {
        ChatClient.Builder selected = Objects.requireNonNull(builder, "builder must not be null");
        synchronized (this.configuredBuilders) {
            if (this.configuredBuilders.containsKey(selected)) {
                throw new IllegalStateException(
                        "PrivacySecurityChatClientConfigurer cannot configure the same ChatClient.Builder more than once"
                );
            }
            this.privacyConfigurer.configure(selected);
            selected.defaultAdvisors(List.of(this.securityAdvisor));
            this.configuredBuilders.put(selected, Boolean.TRUE);
        }
        return selected;
    }
}
