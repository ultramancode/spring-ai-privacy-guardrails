package io.github.ultramancode.springai.privacy.security.autoconfigure;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.function.IntFunction;

/** Creates ChatClients with the starter-managed privacy and tool authorization boundaries. */
public final class PrivacySecurityChatClientFactory {

    // Keep the base privacy starter optional by referring to its JDK function contract.
    private final IntFunction<UnaryOperator<ChatClient.Builder>> privacyConfigurer;
    private final ToolAuthorizationChatClientFactory authorizationFactory;

    PrivacySecurityChatClientFactory(IntFunction<UnaryOperator<ChatClient.Builder>> privacyConfigurer,
            ToolAuthorizationChatClientFactory authorizationFactory) {
        this.privacyConfigurer = Objects.requireNonNull(privacyConfigurer, "privacyConfigurer must not be null");
        this.authorizationFactory = Objects.requireNonNull(authorizationFactory, "authorizationFactory must not be null");
    }

    /**
     * Creates a fresh builder with both boundaries and the starter-managed tool template.
     * @param model the shared model to call
     * @return a new builder with privacy and tool authorization
     */
    public ChatClient.Builder builder(ChatModel model) {
        return builder(model, this.authorizationFactory.toolAdvisorTemplate());
    }

    /**
     * Creates a fresh builder with both boundaries and a copied tool-loop template.
     * Privacy advisors are positioned around the selected tool order without changing
     * application advisor orders. Orders that cannot fit the privacy boundaries are
     * rejected before client customization; the actual request layout is also checked.
     * @param model the shared model to call
     * @param toolAdvisorBuilder standard or Tool Search template with the desired tool order
     * @return a new builder with privacy and tool authorization
     * @throws IllegalArgumentException when the template's tool order is incompatible with privacy
     */
    public ChatClient.Builder builder(ChatModel model, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        ToolCallingAdvisor.Builder<?> template = Objects.requireNonNull(
                toolAdvisorBuilder, "toolAdvisorBuilder must not be null").copy();
        int plannedOrder = template.getAdvisorOrder();
        UnaryOperator<ChatClient.Builder> privacyBoundary = this.privacyConfigurer.apply(plannedOrder);
        return this.authorizationFactory.createBuilder(model, template, privacyBoundary, actualOrder -> {
            if (actualOrder != plannedOrder) {
                throw new IllegalArgumentException("Tool advisor order changed after planning the privacy boundary: "
                        + plannedOrder + " to " + actualOrder);
            }
        });
    }
}
