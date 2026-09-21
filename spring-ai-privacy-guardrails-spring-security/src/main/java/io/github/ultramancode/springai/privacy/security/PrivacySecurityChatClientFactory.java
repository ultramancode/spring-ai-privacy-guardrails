package io.github.ultramancode.springai.privacy.security;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.PriorityOrdered;

import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Creates ChatClients with managed privacy and tool authorization,
 * sharing one model request boundary.
 * Tool advisor builders follow the ordering restrictions documented by
 * {@link ToolAuthorizationChatClientFactory}, including rejection of
 * {@link PriorityOrdered} tool advisors when Spring AI builds the call or stream advisor chain.
 */
public final class PrivacySecurityChatClientFactory {

    // Keep the privacy runtime optional through the common boundary configuration contract.
    private final IntFunction<ModelRequestBoundaryConfigurer> privacyConfigurer;
    private final ToolAuthorizationChatClientFactory authorizationFactory;

    public PrivacySecurityChatClientFactory(IntFunction<ModelRequestBoundaryConfigurer> privacyConfigurer,
            ToolAuthorizationChatClientFactory authorizationFactory) {
        this.privacyConfigurer = Objects.requireNonNull(privacyConfigurer, "privacyConfigurer must not be null");
        this.authorizationFactory = Objects.requireNonNull(authorizationFactory, "authorizationFactory must not be null");
    }

    /**
     * Creates a fresh builder with privacy, tool authorization and the managed tool advisor builder.
     * @param model the shared model to call
     * @return a new builder with privacy and tool authorization
     */
    public ChatClient.Builder builder(ChatModel model) {
        return builder(model, this.authorizationFactory.defaultToolAdvisorBuilder());
    }

    /**
     * Creates a fresh builder with privacy, tool authorization and a copy of the supplied tool advisor builder.
     * Accepts {@link ToolCallingAdvisor.Builder} and subclass builders, including
     * {@code ToolSearchToolCallingAdvisor.Builder}. The copy retains the advisor subtype.
     * Privacy advisors are positioned around the supplied builder's order without changing
     * application advisor orders. Orders that cannot fit the privacy boundaries are
     * rejected before client customization. The actual request layout is also checked.
     * @param model the shared model to call
     * @param toolAdvisorBuilder tool advisor builder whose order determines the privacy layout
     * @return a new builder with privacy and tool authorization
     * @throws IllegalArgumentException when the supplied tool advisor order is incompatible with privacy
     */
    public ChatClient.Builder builder(ChatModel model, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        return builder(model, toolAdvisorBuilder, (clientBuilder, boundarySpec) -> { });
    }

    /** Adds a feature to the same client-scoped model request boundary. */
    public ChatClient.Builder builderWithBoundary(ChatModel model, ModelRequestBoundaryConfigurer configurer) {
        return builder(model, this.authorizationFactory.defaultToolAdvisorBuilder(), configurer);
    }

    /** Composes privacy, authorization and the supplied feature in fixed phase order. */
    public ChatClient.Builder builder(ChatModel model, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder,
            ModelRequestBoundaryConfigurer configurer) {
        ToolCallingAdvisor.Builder<?> toolAdvisorBuilderCopy = Objects.requireNonNull(
                toolAdvisorBuilder, "toolAdvisorBuilder must not be null").copy();
        int plannedToolOrder = toolAdvisorBuilderCopy.getAdvisorOrder();
        ModelRequestBoundaryConfigurer privacyBoundaryConfigurer = this.privacyConfigurer.apply(plannedToolOrder);
        ModelRequestBoundaryConfigurer combinedConfigurer = ModelRequestBoundaryConfigurer.compose(
                privacyBoundaryConfigurer, Objects.requireNonNull(configurer, "configurer"));
        return this.authorizationFactory.createBuilder(model, toolAdvisorBuilderCopy, combinedConfigurer, actualToolOrder -> {
            if (actualToolOrder != plannedToolOrder) {
                throw new IllegalArgumentException("Tool advisor order changed after planning the privacy boundary: "
                        + plannedToolOrder + " to " + actualToolOrder);
            }
        });
    }
}
