package io.github.ultramancode.springai.privacy.autoconfigure;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.UnaryOperator;

/**
 * Applies the starter-managed privacy boundary to a {@link ChatClient.Builder}
 * explicitly selected by the application.
 *
 * <p>The configured advisors are a fixed bundle. Applications may select which
 * builders receive the bundle, but cannot use this API to omit an individual
 * mandatory boundary advisor or change its order. Output protection is included
 * only when {@code spring.ai.privacy.output.enabled=true}.</p>
 */
public final class PrivacyChatClientConfigurer
        implements UnaryOperator<ChatClient.Builder> {

    private final List<Advisor> advisors;
    private final WeakHashMap<ChatClient.Builder, Boolean> configuredBuilders = new WeakHashMap<>();

    PrivacyChatClientConfigurer(List<Advisor> advisors) {
        this.advisors = List.copyOf(Objects.requireNonNull(advisors, "advisors must not be null"));
    }

    /**
     * Applies the complete configured privacy boundary and returns the same builder.
     *
     * @param builder ChatClient builder to configure for privacy protection
     * @return the supplied builder after the privacy advisors have been registered
     * @throws IllegalStateException when the same builder is configured more than once
     */
    public ChatClient.Builder configure(ChatClient.Builder builder) {
        ChatClient.Builder selectedBuilder = Objects.requireNonNull(builder, "builder must not be null");
        synchronized (this.configuredBuilders) {
            if (this.configuredBuilders.containsKey(selectedBuilder)) {
                throw new IllegalStateException(
                        "PrivacyChatClientConfigurer cannot configure the same ChatClient.Builder more than once"
                );
            }
            selectedBuilder.defaultAdvisors(this.advisors);
            this.configuredBuilders.put(selectedBuilder, Boolean.TRUE);
        }
        return selectedBuilder;
    }

    /**
     * Applies the complete configured privacy boundary.
     *
     * @param builder ChatClient builder to configure for privacy protection
     * @return the supplied builder after the privacy advisors have been registered
     */
    @Override
    public ChatClient.Builder apply(ChatClient.Builder builder) {
        return configure(builder);
    }
}
