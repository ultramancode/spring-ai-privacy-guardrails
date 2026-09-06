package io.github.ultramancode.springai.privacy.security.autoconfigure;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Registers the request-scoped advisor required by the starter-managed tool
 * authorization boundary on a supplied {@link ChatClient.Builder}.
 * Auto-configuration separately installs the paired authorization-aware
 * {@code ToolCallingManager}.
 */
public final class ToolAuthorizationChatClientConfigurer {

    private final Advisor toolAuthorizationLifecycleAdvisor;
    private final WeakHashMap<ChatClient.Builder, Boolean> configuredBuilders = new WeakHashMap<>();

    ToolAuthorizationChatClientConfigurer(Advisor toolAuthorizationLifecycleAdvisor) {
        this.toolAuthorizationLifecycleAdvisor = Objects.requireNonNull(
                toolAuthorizationLifecycleAdvisor,
                "toolAuthorizationLifecycleAdvisor must not be null"
        );
    }

    /**
     * Applies request-scoped tool authorization and returns the same builder.
     *
     * @param builder ChatClient builder to configure for tool authorization
     * @return the supplied builder after the authorization advisor has been registered
     * @throws IllegalStateException when the same builder is configured more than once
     */
    public ChatClient.Builder configure(ChatClient.Builder builder) {
        ChatClient.Builder selectedBuilder = Objects.requireNonNull(
                builder,
                "builder must not be null"
        );
        synchronized (this.configuredBuilders) {
            if (this.configuredBuilders.containsKey(selectedBuilder)) {
                throw new IllegalStateException(
                        "ToolAuthorizationChatClientConfigurer cannot configure the same "
                                + "ChatClient.Builder more than once"
                );
            }
            selectedBuilder.defaultAdvisors(List.of(this.toolAuthorizationLifecycleAdvisor));
            this.configuredBuilders.put(selectedBuilder, Boolean.TRUE);
        }
        return selectedBuilder;
    }
}
