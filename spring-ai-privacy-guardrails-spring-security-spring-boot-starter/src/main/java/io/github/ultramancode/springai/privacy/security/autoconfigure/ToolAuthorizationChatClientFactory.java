package io.github.ultramancode.springai.privacy.security.autoconfigure;

import io.github.ultramancode.springai.privacy.security.SpringSecurityToolBoundary;
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
 * Creates selected ChatClients with request-scoped tool authorization. The shared model
 * retains its own manager. Spring AI creates each tool loop and adjusts conversation
 * history for the request's memory advisors.
 *
 * <p>Supply a tool-advisor builder to customize the loop, including Tool Search. Do not
 * register a separate ToolAdvisor. Requests with tools require automatic registration;
 * missing or foreign tool advisors are rejected before standard tool loops run.</p>
 *
 * <p>Tool advisors implementing {@link PriorityOrdered} are
 * unsupported because they run before the authorization lifecycle regardless of their
 * numeric order. They are rejected when Spring AI builds the request's tool advisor,
 * before any model call or tool execution.</p>
 */
public final class ToolAuthorizationChatClientFactory {

    private final SpringSecurityToolBoundary boundary;
    private final ToolCallingAdvisor.Builder<?> toolAdvisorTemplate;
    private final ObservationRegistry observationRegistry;
    private final ChatClientObservationConvention chatClientObservationConvention;
    private final AdvisorObservationConvention advisorObservationConvention;
    private final UnaryOperator<ChatClient.Builder> customize;
    private final boolean automaticToolCalling;

    ToolAuthorizationChatClientFactory(SpringSecurityToolBoundary boundary,
            ToolCallingAdvisor.Builder<?> toolAdvisorTemplate, ObservationRegistry observationRegistry,
            ChatClientObservationConvention chatClientObservationConvention,
            AdvisorObservationConvention advisorObservationConvention,
            UnaryOperator<ChatClient.Builder> customize, boolean automaticToolCalling) {
        this.boundary = Objects.requireNonNull(boundary, "boundary must not be null");
        this.toolAdvisorTemplate = Objects.requireNonNull(toolAdvisorTemplate, "toolAdvisorTemplate must not be null").copy();
        this.observationRegistry = Objects.requireNonNull(observationRegistry, "observationRegistry must not be null");
        this.chatClientObservationConvention = chatClientObservationConvention;
        this.advisorObservationConvention = advisorObservationConvention;
        this.customize = Objects.requireNonNull(customize, "customize must not be null");
        this.automaticToolCalling = automaticToolCalling;
    }

    /**
     * Creates a fresh builder using the starter-managed tool template and client defaults.
     * @param model the shared model to call
     * @return a new builder with scoped tool authorization
     */
    public ChatClient.Builder builder(ChatModel model) {
        return builder(model, this.toolAdvisorTemplate);
    }

    /**
     * Creates a fresh builder using a copy of the supplied tool-loop template. Spring AI
     * determines conversation history handling from the final request advisor chain.
     * Custom templates must honor the standard copy, manager and build contracts.
     * @param model the shared model to call
     * @param toolAdvisorBuilder template for a standard or Tool Search loop
     * @return a new builder with scoped tool authorization
     * @throws IllegalArgumentException when the tool order is outside the authorization boundaries
     */
    public ChatClient.Builder builder(ChatModel model, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        return createBuilder(model, toolAdvisorBuilder, UnaryOperator.identity(), ignored -> { });
    }

    ToolCallingAdvisor.Builder<?> toolAdvisorTemplate() {
        return this.toolAdvisorTemplate;
    }

    ChatClient.Builder createBuilder(ChatModel model, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder,
            UnaryOperator<ChatClient.Builder> additionalAdvisorConfigurer, IntConsumer additionalOrderValidator) {
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(toolAdvisorBuilder, "toolAdvisorBuilder must not be null");
        IntConsumer orderValidator = order -> {
            if (order <= this.boundary.toolAuthorizationAdvisor().getOrder()
                    || order >= this.boundary.toolDefinitionAuthorizationAdvisor().getOrder()) {
                throw new IllegalArgumentException(
                        "Tool-calling advisor must run after authorization lifecycle and before definition authorization");
            }
            additionalOrderValidator.accept(order);
        };
        ToolAuthorizationChainAdvisor chainValidator = new ToolAuthorizationChainAdvisor();
        AuthorizedToolCallingAdvisorBuilder securedTemplate = new AuthorizedToolCallingAdvisorBuilder(
                toolAdvisorBuilder, this.boundary.toolCallingManager(), chainValidator, orderValidator);
        ChatClient.Builder builder = ChatClient.builder(model, this.observationRegistry,
                this.chatClientObservationConvention, this.advisorObservationConvention, securedTemplate)
                .defaultAdvisors(chainValidator);
        if (!this.automaticToolCalling) {
            builder.defaultAdvisors(AdvisorParams.toolCallingAdvisorAutoRegister(false));
        }
        this.customize.apply(builder);
        // The privacy model boundary must validate the original callbacks before the
        // definition advisor filters them. Their terminal order is intentionally equal.
        additionalAdvisorConfigurer.apply(builder);
        return builder.defaultAdvisors(this.boundary.toolAuthorizationAdvisor(),
                this.boundary.toolDefinitionAuthorizationAdvisor());
    }
}
