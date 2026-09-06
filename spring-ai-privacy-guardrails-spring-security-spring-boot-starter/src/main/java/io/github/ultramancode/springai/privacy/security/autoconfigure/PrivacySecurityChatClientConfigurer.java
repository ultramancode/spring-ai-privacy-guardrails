package io.github.ultramancode.springai.privacy.security.autoconfigure;

import org.springframework.ai.chat.client.ChatClient;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Applies the starter-managed privacy advisors and tool-authorization advisor
 * to a supplied {@link ChatClient.Builder}.
 */
public final class PrivacySecurityChatClientConfigurer {

    // Uses a JDK function to keep the base privacy starter optional.
    private final UnaryOperator<ChatClient.Builder> privacyChatClientConfigurer;
    private final ToolAuthorizationChatClientConfigurer toolAuthorizationChatClientConfigurer;

    PrivacySecurityChatClientConfigurer(
            UnaryOperator<ChatClient.Builder> privacyChatClientConfigurer,
            ToolAuthorizationChatClientConfigurer toolAuthorizationChatClientConfigurer
    ) {
        this.privacyChatClientConfigurer = Objects.requireNonNull(
                privacyChatClientConfigurer,
                "privacyChatClientConfigurer must not be null"
        );
        this.toolAuthorizationChatClientConfigurer = Objects.requireNonNull(
                toolAuthorizationChatClientConfigurer,
                "toolAuthorizationChatClientConfigurer must not be null"
        );
    }

    /**
     * Applies privacy and tool authorization to the supplied builder.
     *
     * @param builder ChatClient builder to configure for privacy and tool authorization
     * @return the supplied builder after its privacy and tool-authorization advisors
     * have been registered
     * @throws IllegalStateException when the same builder is configured more than once
     */
    public ChatClient.Builder configure(ChatClient.Builder builder) {
        ChatClient.Builder selectedBuilder = Objects.requireNonNull(
                builder,
                "builder must not be null"
        );
        this.privacyChatClientConfigurer.apply(selectedBuilder);
        return this.toolAuthorizationChatClientConfigurer.configure(selectedBuilder);
    }
}
