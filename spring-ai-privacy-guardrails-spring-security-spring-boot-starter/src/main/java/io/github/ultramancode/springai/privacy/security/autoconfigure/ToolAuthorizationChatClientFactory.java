package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.security.SpringSecurityToolBoundary;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.core.PriorityOrdered;

import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.UnaryOperator;

/**
 * Creates selected ChatClients with request-scoped tool authorization. The shared model
 * retains its existing {@link ToolCallingManager}. Spring AI adjusts the tool advisor's
 * conversation history handling based on the request's memory advisors.
 *
 * <p>Supply a tool advisor builder to customize the loop, including Tool Search.
 * By default, Spring AI builds and registers the advisor for each call or stream chain,
 * even for requests without tools. Requests with tools require automatic registration.
 * Tool advisors registered separately from this client's secured builder are rejected
 * before standard tool loops run.</p>
 *
 * <p>Tool advisors implementing {@link PriorityOrdered} are
 * unsupported because they run before the authorization lifecycle regardless of their
 * numeric order. They are rejected when Spring AI builds the request's tool advisor,
 * before any model call or tool execution.</p>
 */
public final class ToolAuthorizationChatClientFactory {

    private final SpringSecurityToolBoundary boundary;
    private final ToolCallingAdvisor.Builder<?> defaultToolAdvisorBuilder;
    private final ObservationRegistry observationRegistry;
    private final ChatClientObservationConvention chatClientObservationConvention;
    private final AdvisorObservationConvention advisorObservationConvention;
    private final UnaryOperator<ChatClient.Builder> clientBuilderCustomizer;
    private final boolean autoRegisterToolAdvisor;

    ToolAuthorizationChatClientFactory(SpringSecurityToolBoundary boundary,
            ToolCallingAdvisor.Builder<?> defaultToolAdvisorBuilder, ObservationRegistry observationRegistry,
            ChatClientObservationConvention chatClientObservationConvention,
            AdvisorObservationConvention advisorObservationConvention,
            UnaryOperator<ChatClient.Builder> clientBuilderCustomizer, boolean autoRegisterToolAdvisor) {
        this.boundary = Objects.requireNonNull(boundary, "boundary must not be null");
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
     * Creates a fresh builder using the starter-managed tool advisor builder and client defaults.
     * @param model the shared model to call
     * @return a new builder with scoped tool authorization
     */
    public ChatClient.Builder builder(ChatModel model) {
        return builder(model, this.defaultToolAdvisorBuilder);
    }

    /**
     * Creates a fresh builder using a copy of the supplied tool advisor builder.
     * Accepts {@link ToolCallingAdvisor.Builder} and subclass builders, including
     * {@code ToolSearchToolCallingAdvisor.Builder}. The copy retains the advisor subtype.
     * Spring AI determines conversation history handling from the final request advisor chain.
     * Custom builders must honor the standard contracts of {@code copy()},
     * {@code toolCallingManager(...)} and {@code build()}.
     * @param model the shared model to call
     * @param toolAdvisorBuilder builder for a standard or custom tool loop, including Tool Search
     * @return a new builder with scoped tool authorization
     * @throws IllegalArgumentException when the tool order is outside the authorization boundaries
     */
    public ChatClient.Builder builder(ChatModel model, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        return createBuilder(model, toolAdvisorBuilder, UnaryOperator.identity(), ignored -> { });
    }

    ToolCallingAdvisor.Builder<?> defaultToolAdvisorBuilder() {
        return this.defaultToolAdvisorBuilder;
    }

    ChatClient.Builder createBuilder(ChatModel model, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder,
            UnaryOperator<ChatClient.Builder> additionalAdvisorConfigurer, IntConsumer additionalOrderValidator) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(toolAdvisorBuilder, "toolAdvisorBuilder must not be null");
        IntConsumer orderValidator = order -> {
            if (order <= this.boundary.toolAuthorizationAdvisor().getOrder()
                    || order >= this.boundary.toolDefinitionAuthorizationAdvisor().getOrder()) {
                throw new IllegalArgumentException(
                        "Tool advisor must run after authorization lifecycle and before definition authorization");
            }
            additionalOrderValidator.accept(order);
        };
        ToolAuthorizationChainAdvisor chainValidator = new ToolAuthorizationChainAdvisor();
        AuthorizedToolCallingAdvisorBuilder authorizedToolAdvisorBuilder = new AuthorizedToolCallingAdvisorBuilder(
                toolAdvisorBuilder, this.boundary.toolCallingManager(), chainValidator, orderValidator);
        ChatClient.Builder builder = ChatClient.builder(model, this.observationRegistry,
                this.chatClientObservationConvention, this.advisorObservationConvention, authorizedToolAdvisorBuilder)
                .defaultAdvisors(chainValidator);
        if (!this.autoRegisterToolAdvisor) {
            builder.defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false));
        }
        this.clientBuilderCustomizer.apply(builder);
        // The privacy model boundary must validate the original callbacks before the
        // definition advisor filters them. Their terminal order is intentionally equal.
        additionalAdvisorConfigurer.apply(builder);
        return builder.defaultAdvisors(this.boundary.toolAuthorizationAdvisor(),
                this.boundary.toolDefinitionAuthorizationAdvisor());
    }
}
