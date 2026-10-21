package io.github.ultramancode.springai.privacy.security;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.PriorityOrdered;

import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Creates {@link ChatClient} builders with privacy protection and tool authorization
 * in one model request boundary.
 * Tool advisor builders follow the ordering restrictions documented by
 * {@link ToolAuthorizationChatClientFactory}, including rejection of
 * {@link PriorityOrdered} tool advisors.
 */
public final class PrivacySecurityChatClientFactory {

    // Keep the privacy runtime optional through the common boundary configuration contract.
    private final IntFunction<ModelRequestBoundaryConfigurer> privacyConfigurerFactory;
    private final ToolAuthorizationChatClientFactory toolAuthorizationFactory;

    /**
     * Combines privacy boundary configuration with tool authorization.
     * @param privacyConfigurerFactory creates privacy configuration for the tool advisor's execution order
     * @param toolAuthorizationFactory supplies tool authorization and client defaults
     */
    public PrivacySecurityChatClientFactory(IntFunction<ModelRequestBoundaryConfigurer> privacyConfigurerFactory,
        ToolAuthorizationChatClientFactory toolAuthorizationFactory) {
        this.privacyConfigurerFactory = Objects.requireNonNull(
            privacyConfigurerFactory, "privacyConfigurerFactory must not be null");
        this.toolAuthorizationFactory = Objects.requireNonNull(
            toolAuthorizationFactory, "toolAuthorizationFactory must not be null");
    }

    /**
     * Creates a fresh builder with privacy, tool authorization and the managed tool advisor builder.
     * @param chatModel the shared model to call
     * @return a new builder with privacy and tool authorization
     */
    public ChatClient.Builder builder(ChatModel chatModel) {
        return builder(chatModel, this.toolAuthorizationFactory.defaultToolAdvisorBuilder());
    }

    /**
     * Creates a fresh builder with privacy, tool authorization and a copy of the supplied tool advisor builder.
     * Accepts {@link ToolCallingAdvisor.Builder} and subclass builders, including
     * {@code ToolSearchToolCallingAdvisor.Builder}. The copy retains the advisor subtype.
     * Privacy advisors are positioned around the supplied builder's order without changing
     * application advisor orders. Orders outside the supported privacy boundaries are rejected.
     * @param chatModel the shared model to call
     * @param toolAdvisorBuilder tool advisor builder whose order determines the privacy layout
     * @return a new builder with privacy and tool authorization
     * @throws IllegalArgumentException when the supplied tool advisor order is incompatible with privacy
     */
    public ChatClient.Builder builder(ChatModel chatModel, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        return builder(chatModel, toolAdvisorBuilder, (chatClientBuilder, boundarySpec) -> { });
    }

    /**
     * Composes privacy, authorization and additional features in one model request boundary.
     * Do not apply another boundary configurer separately to the returned builder.
     * @param chatModel the shared model to call
     * @param toolAdvisorBuilder tool advisor builder to copy and configure
     * @param additionalBoundaryConfigurer additional features, such as inspection.
     *         Must not register privacy or authorization again.
     * @return a new builder with the selected tool loop and combined boundary
     */
    public ChatClient.Builder builder(ChatModel chatModel, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder,
        ModelRequestBoundaryConfigurer additionalBoundaryConfigurer) {
        ToolCallingAdvisor.Builder<?> clientToolAdvisorBuilder = Objects.requireNonNull(
            toolAdvisorBuilder, "toolAdvisorBuilder must not be null").copy();
        int plannedToolAdvisorOrder = clientToolAdvisorBuilder.getAdvisorOrder();

        ModelRequestBoundaryConfigurer privacyBoundaryConfigurer =
            this.privacyConfigurerFactory.apply(plannedToolAdvisorOrder);
        Objects.requireNonNull(additionalBoundaryConfigurer, "additionalBoundaryConfigurer");
        ModelRequestBoundaryConfigurer combinedBoundaryConfigurer = ModelRequestBoundaryConfigurer.compose(
            privacyBoundaryConfigurer, additionalBoundaryConfigurer);

        IntConsumer toolAdvisorOrderValidator = actualToolAdvisorOrder -> {
            if (actualToolAdvisorOrder != plannedToolAdvisorOrder) {
                throw new IllegalArgumentException("Tool advisor order changed after planning the privacy boundary: "
                    + plannedToolAdvisorOrder + " to " + actualToolAdvisorOrder);
            }
        };

        return this.toolAuthorizationFactory.createAuthorizedClientBuilder(
            chatModel,
            clientToolAdvisorBuilder,
            combinedBoundaryConfigurer,
            toolAdvisorOrderValidator);
    }

    /**
     * Adds features to the same model request boundary using the default tool advisor builder.
     * @param chatModel the shared model to call
     * @param additionalBoundaryConfigurer additional features, such as inspection.
     *         Must not register privacy or authorization again.
     * @return a new builder with privacy, authorization and the additional features
     */
    public ChatClient.Builder builderWithBoundary(ChatModel chatModel,
        ModelRequestBoundaryConfigurer additionalBoundaryConfigurer) {
        return builder(chatModel, this.toolAuthorizationFactory.defaultToolAdvisorBuilder(), additionalBoundaryConfigurer);
    }
}
