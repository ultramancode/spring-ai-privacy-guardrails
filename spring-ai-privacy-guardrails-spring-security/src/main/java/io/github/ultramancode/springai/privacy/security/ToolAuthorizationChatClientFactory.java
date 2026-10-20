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
    private final UnaryOperator<ChatClient.Builder> clientBuilderCustomizer;
    private final boolean autoRegisterToolAdvisor;

    /** Creates a programmatic client factory without requiring Spring Boot. */
    public ToolAuthorizationChatClientFactory(SpringSecurityToolBoundary toolAuthorizationBoundary) {
        this(toolAuthorizationBoundary, ToolCallingAdvisor.builder(), ObservationRegistry.NOOP,
            null, null, UnaryOperator.identity(), true);
    }

    /** Creates a factory with application observation, defaults and client customization. */
    public ToolAuthorizationChatClientFactory(SpringSecurityToolBoundary toolAuthorizationBoundary,
        ToolCallingAdvisor.Builder<?> defaultToolAdvisorBuilder, ObservationRegistry observationRegistry,
        ChatClientObservationConvention chatClientObservationConvention,
        AdvisorObservationConvention advisorObservationConvention,
        UnaryOperator<ChatClient.Builder> clientBuilderCustomizer, boolean autoRegisterToolAdvisor) {
        this.toolAuthorizationBoundary = Objects.requireNonNull(
            toolAuthorizationBoundary, "toolAuthorizationBoundary must not be null");
        this.defaultToolAdvisorBuilder = Objects.requireNonNull(
                defaultToolAdvisorBuilder, "defaultToolAdvisorBuilder must not be null").copy();
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry must not be null");
        this.chatClientObservationConvention = chatClientObservationConvention;
        this.advisorObservationConvention = advisorObservationConvention;
        this.clientBuilderCustomizer = Objects.requireNonNull(
                clientBuilderCustomizer, "clientBuilderCustomizer must not be null");
        this.autoRegisterToolAdvisor = autoRegisterToolAdvisor;
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
        return createBuilder(chatModel, toolAdvisorBuilder, (chatClientBuilder, boundarySpec) -> { }, ignored -> { });
    }

    ToolCallingAdvisor.Builder<?> defaultToolAdvisorBuilder() {
        return this.defaultToolAdvisorBuilder;
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
     * Preserves a caller-selected tool loop, including Tool Search, while composing model stages.
     * Do not apply another boundary configurer separately to the returned builder.
     * @param chatModel the shared model to call
     * @param toolAdvisorBuilder tool advisor builder to copy and configure
     * @param additionalBoundaryConfigurer additional features. Must not register authorization again.
     * @return a new builder with the selected tool loop and combined boundary
     */
    public ChatClient.Builder builder(ChatModel chatModel, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder,
        ModelRequestBoundaryConfigurer additionalBoundaryConfigurer) {
        return createBuilder(chatModel, toolAdvisorBuilder,
            Objects.requireNonNull(additionalBoundaryConfigurer, "additionalBoundaryConfigurer"), ignored -> { });
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

    ChatClient.Builder createBuilder(ChatModel chatModel, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder,
        ModelRequestBoundaryConfigurer additionalBoundaryConfigurer, IntConsumer additionalToolAdvisorOrderValidator) {
        Objects.requireNonNull(chatModel, "chatModel must not be null");
        ToolAuthorizationConfiguration toolAuthorizationConfiguration =
            prepareToolAuthorization(toolAdvisorBuilder, additionalToolAdvisorOrderValidator);
        ChatClient.Builder chatClientBuilder =
            createClientBuilder(chatModel, toolAuthorizationConfiguration.toolAdvisorBuilder());
        return ModelRequestBoundaryConfigurer.compose(
            toolAuthorizationConfiguration.boundaryConfigurer(), additionalBoundaryConfigurer)
            .configure(chatClientBuilder);
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

    private ChatClient.Builder createClientBuilder(ChatModel chatModel, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        ChatClient.Builder chatClientBuilder = ChatClient.builder(chatModel, this.observationRegistry,
            this.chatClientObservationConvention, this.advisorObservationConvention, toolAdvisorBuilder);
        if (!this.autoRegisterToolAdvisor) {
            chatClientBuilder.defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false));
        }
        chatClientBuilder = Objects.requireNonNull(this.clientBuilderCustomizer.apply(chatClientBuilder),
            "clientBuilderCustomizer must not return null");
        return chatClientBuilder;
    }
}
