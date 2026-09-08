package io.github.ultramancode.springai.privacy.security.autoconfigure;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.PriorityOrdered;

import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.function.IntFunction;

/**
 * Creates ChatClients with the starter-managed privacy and tool authorization boundaries.
 * Tool advisor templates follow the ordering restrictions documented by
 * {@link ToolAuthorizationChatClientFactory}, including rejection of
 * {@link PriorityOrdered} tool advisors when Spring AI builds the call or stream advisor chain.
 */
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
     * Creates a fresh builder with both boundaries and a copy of the supplied tool advisor builder.
     * Accepts {@link ToolCallingAdvisor.Builder} and subclass builders, including
     * {@code ToolSearchToolCallingAdvisor.Builder}. The copy retains the advisor subtype.
     * Privacy advisors are positioned around the supplied builder's order without changing
     * application advisor orders. Orders that cannot fit the privacy boundaries are
     * rejected before client customization. The actual request layout is also checked.
     * @param model the shared model to call
     * @param toolAdvisorBuilder tool advisor builder whose order determines the privacy layout
     * @return a new builder with privacy and tool authorization
     * @throws IllegalArgumentException when the template's tool order is incompatible with privacy
     */
    public ChatClient.Builder builder(ChatModel model, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        ToolCallingAdvisor.Builder<?> template = Objects.requireNonNull(
                toolAdvisorBuilder, "toolAdvisorBuilder must not be null").copy();
        int plannedToolOrder = template.getAdvisorOrder();
        UnaryOperator<ChatClient.Builder> privacyAdvisorConfigurer = this.privacyConfigurer.apply(plannedToolOrder);
        return this.authorizationFactory.createBuilder(model, template, privacyAdvisorConfigurer, actualToolOrder -> {
            if (actualToolOrder != plannedToolOrder) {
                throw new IllegalArgumentException("Tool advisor order changed after planning the privacy boundary: "
                        + plannedToolOrder + " to " + actualToolOrder);
            }
        });
    }
}
