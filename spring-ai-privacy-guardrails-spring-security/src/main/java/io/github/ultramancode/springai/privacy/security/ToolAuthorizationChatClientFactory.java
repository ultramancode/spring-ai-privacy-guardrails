package io.github.ultramancode.springai.privacy.security;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundarySpec;
import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.boundary.ToolAuthorizationConfiguration;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.PriorityOrdered;

import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;

/**
 * Creates ChatClient builders with request-scoped tool authorization.
 * Use {@link #builder()} to configure the factory and {@link #builder(ChatModel)} to create client builders.
 *
 * <p>Supply a tool advisor builder to customize the loop, including Tool Search.
 * Tool calls must use the advisor created by this factory.</p>
 *
 * <p>Tool advisors implementing {@link PriorityOrdered} are
 * unsupported because they run before the authorization lifecycle.</p>
 */
public final class ToolAuthorizationChatClientFactory {

    private final SpringSecurityToolBoundary toolAuthorizationBoundary;
    private final ToolCallingAdvisor.Builder<?> defaultToolAdvisorBuilder;
    private final ObservationRegistry observationRegistry;
    private final ChatClientObservationConvention chatClientObservationConvention;
    private final AdvisorObservationConvention advisorObservationConvention;
    private final UnaryOperator<ChatClient.Builder> clientCustomizer;
    private final boolean autoRegisterToolAdvisorEnabled;

    private ToolAuthorizationChatClientFactory(Builder factoryBuilder) {
        this.toolAuthorizationBoundary = factoryBuilder.toolAuthorizationBoundary;
        this.defaultToolAdvisorBuilder = factoryBuilder.defaultToolAdvisorBuilder.copy();
        this.observationRegistry = factoryBuilder.observationRegistry;
        this.chatClientObservationConvention = factoryBuilder.chatClientObservationConvention;
        this.advisorObservationConvention = factoryBuilder.advisorObservationConvention;
        this.clientCustomizer = factoryBuilder.clientCustomizer;
        this.autoRegisterToolAdvisorEnabled = factoryBuilder.autoRegisterToolAdvisorEnabled;
    }

    /** Creates a factory builder for tool authorization, client defaults and observation settings. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a fresh builder using the managed tool advisor builder and client defaults.
     * @param chatModel the shared model to call
     * @return a new builder with scoped tool authorization
     */
    public ChatClient.Builder builder(ChatModel chatModel) {
        return builder(chatModel, this.defaultToolAdvisorBuilder);
    }

    /**
     * Creates a fresh builder using a copy of the supplied tool advisor builder.
     * Accepts {@link ToolCallingAdvisor.Builder} and subclass builders, including
     * {@code ToolSearchToolCallingAdvisor.Builder}. The copy retains the advisor subtype.
     * Custom builders must honor the standard contracts of {@code copy()},
     * {@code toolCallingManager(...)} and {@code build()}.
     * @param chatModel the shared model to call
     * @param toolAdvisorBuilder builder for a standard or custom tool loop, including Tool Search
     * @return a new builder with scoped tool authorization
     * @throws IllegalArgumentException when the tool order is outside the authorization boundaries
     */
    public ChatClient.Builder builder(ChatModel chatModel, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        return createAuthorizedClientBuilder(chatModel, toolAdvisorBuilder,
            (chatClientBuilder, boundarySpec) -> { }, ignored -> { });
    }

    /**
     * Preserves a caller-selected tool loop, including Tool Search, while composing model stages.
     * Do not apply another boundary configurer separately to the returned builder.
     * @param chatModel the shared model to call
     * @param toolAdvisorBuilder tool advisor builder to copy and configure
     * @param additionalBoundaryConfigurer additional features. Must not register authorization again.
     * @return a new builder with the selected tool loop and combined boundary
     */
    public ChatClient.Builder builder(ChatModel chatModel, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder,
        ModelRequestBoundaryConfigurer additionalBoundaryConfigurer) {
        Objects.requireNonNull(additionalBoundaryConfigurer, "additionalBoundaryConfigurer");
        return createAuthorizedClientBuilder(chatModel, toolAdvisorBuilder,
            additionalBoundaryConfigurer, ignored -> { });
    }

    /**
     * Creates a builder that combines tool authorization and the supplied features
     * in one model request boundary using the default tool advisor builder.
     * @param chatModel the shared model to call
     * @param additionalBoundaryConfigurer additional features. Must not register authorization again.
     * @return a new builder with authorization and the additional features
     */
    public ChatClient.Builder builderWithBoundary(ChatModel chatModel, ModelRequestBoundaryConfigurer additionalBoundaryConfigurer) {
        return builder(chatModel, this.defaultToolAdvisorBuilder, additionalBoundaryConfigurer);
    }

    /**
     * Creates a new authorization configuration from a copy of the supplied tool advisor builder.
     *
     * @param toolAdvisorBuilder builder for the selected tool loop, including Tool Search
     * @return the tool advisor builder and its matching authorization boundary
     */
    public ToolAuthorizationConfiguration prepareToolAuthorization(ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        return prepareToolAuthorization(toolAdvisorBuilder, ignored -> { });
    }

    private ToolAuthorizationConfiguration prepareToolAuthorization(ToolCallingAdvisor.Builder<?> toolAdvisorBuilder,
        IntConsumer additionalToolAdvisorOrderValidator) {
        Objects.requireNonNull(toolAdvisorBuilder, "toolAdvisorBuilder must not be null");
        IntConsumer toolAdvisorOrderValidator = order -> {
            if (order <= this.toolAuthorizationBoundary.toolAuthorizationAdvisor().getOrder()
                || order >= ModelRequestBoundarySpec.DEFAULT_ORDER) {
                throw new IllegalArgumentException(
                    "Tool advisor must run after authorization lifecycle and before definition authorization");
            }
            additionalToolAdvisorOrderValidator.accept(order);
        };
        ToolAuthorizationChainAdvisor toolAuthorizationChainAdvisor = new ToolAuthorizationChainAdvisor();
        AuthorizedToolCallingAdvisorBuilder authorizedToolAdvisorBuilder = new AuthorizedToolCallingAdvisorBuilder(
            toolAdvisorBuilder, this.toolAuthorizationBoundary.toolCallingManager(),
            toolAuthorizationChainAdvisor, toolAdvisorOrderValidator);
        ModelRequestBoundaryConfigurer authorizationBoundaryConfigurer =
            this.toolAuthorizationBoundary.modelRequestBoundaryConfigurer();
        return new ToolAuthorizationConfiguration(
            authorizedToolAdvisorBuilder,
            (chatClientBuilder, boundarySpec) -> {
                chatClientBuilder.defaultAdvisors(toolAuthorizationChainAdvisor);
                authorizationBoundaryConfigurer.contributeToBoundary(chatClientBuilder, boundarySpec);
            });
    }

    ChatClient.Builder createAuthorizedClientBuilder(ChatModel chatModel, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder,
        ModelRequestBoundaryConfigurer additionalBoundaryConfigurer, IntConsumer additionalToolAdvisorOrderValidator) {
        Objects.requireNonNull(chatModel, "chatModel must not be null");
        ToolAuthorizationConfiguration toolAuthorizationConfiguration =
            prepareToolAuthorization(toolAdvisorBuilder, additionalToolAdvisorOrderValidator);
        ChatClient.Builder chatClientBuilder =
            createBaseClientBuilder(chatModel, toolAuthorizationConfiguration.toolAdvisorBuilder());
        return ModelRequestBoundaryConfigurer.compose(
            toolAuthorizationConfiguration.modelRequestBoundaryConfigurer(), additionalBoundaryConfigurer)
            .configure(chatClientBuilder);
    }

    private ChatClient.Builder createBaseClientBuilder(ChatModel chatModel, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        ChatClient.Builder chatClientBuilder = ChatClient.builder(chatModel, this.observationRegistry,
            this.chatClientObservationConvention, this.advisorObservationConvention, toolAdvisorBuilder);
        if (!this.autoRegisterToolAdvisorEnabled) {
            chatClientBuilder.defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false));
        }
        chatClientBuilder = Objects.requireNonNull(this.clientCustomizer.apply(chatClientBuilder),
            "clientCustomizer must not return null");
        return chatClientBuilder;
    }

    ToolCallingAdvisor.Builder<?> defaultToolAdvisorBuilder() {
        return this.defaultToolAdvisorBuilder;
    }

    /** Builds a {@link ToolAuthorizationChatClientFactory} with shared authorization and client defaults. */
    public static final class Builder {
        private SpringSecurityToolBoundary toolAuthorizationBoundary;
        private ToolCallingAdvisor.Builder<?> defaultToolAdvisorBuilder = ToolCallingAdvisor.builder();
        private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;
        private ChatClientObservationConvention chatClientObservationConvention;
        private AdvisorObservationConvention advisorObservationConvention;
        private UnaryOperator<ChatClient.Builder> clientCustomizer = UnaryOperator.identity();
        private boolean autoRegisterToolAdvisorEnabled = true;

        private Builder() {}

        /** Sets the authorization boundary used by clients created by this factory. */
        public Builder toolAuthorizationBoundary(SpringSecurityToolBoundary toolAuthorizationBoundary) {
            this.toolAuthorizationBoundary = Objects.requireNonNull(toolAuthorizationBoundary, "toolAuthorizationBoundary");
            return this;
        }

        /** Sets the default tool advisor builder for clients created by this factory. */
        public Builder toolAdvisorBuilder(ToolCallingAdvisor.Builder<?> defaultToolAdvisorBuilder) {
            // Keep these defaults independent of later changes to the supplied builder.
            this.defaultToolAdvisorBuilder = Objects.requireNonNull(
                defaultToolAdvisorBuilder, "defaultToolAdvisorBuilder").copy();
            return this;
        }

        public Builder observationRegistry(ObservationRegistry observationRegistry) {
            this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry");
            return this;
        }

        public Builder chatClientObservationConvention(ChatClientObservationConvention chatClientObservationConvention) {
            this.chatClientObservationConvention = chatClientObservationConvention;
            return this;
        }

        public Builder advisorObservationConvention(AdvisorObservationConvention advisorObservationConvention) {
            this.advisorObservationConvention = advisorObservationConvention;
            return this;
        }

        public Builder clientCustomizer(UnaryOperator<ChatClient.Builder> clientCustomizer) {
            this.clientCustomizer = Objects.requireNonNull(clientCustomizer, "clientCustomizer");
            return this;
        }

        /**
         * Sets whether to register the configured tool advisor automatically. Defaults to {@code true}.
         * Requests with tools are rejected before the model call when automatic registration is disabled.
         */
        public Builder autoRegisterToolAdvisor(boolean autoRegisterToolAdvisorEnabled) {
            this.autoRegisterToolAdvisorEnabled = autoRegisterToolAdvisorEnabled;
            return this;
        }

        /**
         * Creates a factory from this builder.
         * @throws IllegalStateException if no authorization boundary is configured
         */
        public ToolAuthorizationChatClientFactory build() {
            if (toolAuthorizationBoundary == null) {
                throw new IllegalStateException("No SpringSecurityToolBoundary is configured");
            }
            return new ToolAuthorizationChatClientFactory(this);
        }
    }
}
