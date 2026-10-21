package io.github.ultramancode.springai.privacy.chatclient;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.boundary.ToolAuthorizationConfiguration;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionChatClientConfigurer;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionObserver;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionOutputAdvisor;
import io.github.ultramancode.springai.privacy.inspection.springai.PrivacyProcessingStatusResolver;
import io.github.ultramancode.springai.privacy.springai.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyEnforcementObserver;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.observation.AdvisorObservationConvention;
import org.springframework.ai.chat.client.observation.ChatClientObservationConvention;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Creates {@link ChatClient} builders with the features selected by a {@link GuardrailsPolicy}.
 * Privacy-enabled clients protect tools registered through {@code defaultTools(...)} or request-specific
 * {@code tools(...)} calls. Supply callbacks and providers that have not been wrapped
 * by {@link PrivacyToolCallbackFactory}.
 */
public final class GuardrailsChatClientFactory {
    // Privacy
    private final PrivacyService privacyService;
    private final PrivacyEnforcementObserver privacyObserver;

    // Inspection
    private final InspectionService inspectionService;
    private final InspectionObserver inspectionObserver;

    // Tool authorization
    private final ToolAuthorizationConfigurer toolAuthorizationConfigurer;

    // Client defaults
    private final GuardrailsPolicy defaultPolicy;
    private final ToolCallingAdvisor.Builder<?> defaultToolAdvisorBuilder;
    private final ObservationRegistry observationRegistry;
    private final ChatClientObservationConvention chatClientObservationConvention;
    private final AdvisorObservationConvention advisorObservationConvention;
    private final UnaryOperator<ChatClient.Builder> clientCustomizer;

    // Factory construction
    private GuardrailsChatClientFactory(Builder factoryBuilder) {
        this.privacyService = factoryBuilder.privacyService;
        this.privacyObserver = factoryBuilder.privacyObserver;

        this.inspectionService = factoryBuilder.inspectionService;
        this.inspectionObserver = factoryBuilder.inspectionObserver;

        this.toolAuthorizationConfigurer = factoryBuilder.toolAuthorizationConfigurer;

        this.defaultPolicy = factoryBuilder.defaultPolicy;
        this.defaultToolAdvisorBuilder = factoryBuilder.defaultToolAdvisorBuilder.copy();
        this.observationRegistry = factoryBuilder.observationRegistry;
        this.chatClientObservationConvention = factoryBuilder.chatClientObservationConvention;
        this.advisorObservationConvention = factoryBuilder.advisorObservationConvention;
        this.clientCustomizer = factoryBuilder.clientCustomizer;
    }

    /** Creates a factory builder for shared services, client defaults and an optional default policy. */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds a {@link GuardrailsChatClientFactory} with shared services and client defaults. */
    public static final class Builder {
        // Privacy
        private PrivacyService privacyService;
        private PrivacyEnforcementObserver privacyObserver = PrivacyEnforcementObserver.noop();

        // Inspection
        private InspectionService inspectionService;
        private InspectionObserver inspectionObserver = InspectionObserver.noop();

        // Tool authorization
        private ToolAuthorizationConfigurer toolAuthorizationConfigurer;

        // Client defaults
        private GuardrailsPolicy defaultPolicy;
        private ToolCallingAdvisor.Builder<?> defaultToolAdvisorBuilder = ToolCallingAdvisor.builder();
        private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;
        private ChatClientObservationConvention chatClientObservationConvention;
        private AdvisorObservationConvention advisorObservationConvention;
        private UnaryOperator<ChatClient.Builder> clientCustomizer = UnaryOperator.identity();

        private Builder() {}

        public Builder privacyService(PrivacyService privacyService) {
            this.privacyService = Objects.requireNonNull(privacyService, "privacyService");
            return this;
        }

        public Builder privacyObserver(PrivacyEnforcementObserver privacyObserver) {
            this.privacyObserver = Objects.requireNonNull(privacyObserver, "privacyObserver");
            return this;
        }

        public Builder inspectionService(InspectionService inspectionService) {
            this.inspectionService = Objects.requireNonNull(inspectionService, "inspectionService");
            return this;
        }

        public Builder inspectionObserver(InspectionObserver inspectionObserver) {
            this.inspectionObserver = Objects.requireNonNull(inspectionObserver, "inspectionObserver");
            return this;
        }

        /**
         * Sets the authorization integration used when a client policy enables tool authorization.
         * For programmatic configuration with the Security module, pass
         * {@code toolAuthorizationChatClientFactory::prepareToolAuthorization}.
         *
         * @param toolAuthorizationConfigurer function that prepares tool authorization
         * @return this builder
         */
        public Builder toolAuthorization(ToolAuthorizationConfigurer toolAuthorizationConfigurer) {
            this.toolAuthorizationConfigurer = Objects.requireNonNull(
                toolAuthorizationConfigurer, "toolAuthorizationConfigurer");
            return this;
        }

        public Builder defaultPolicy(GuardrailsPolicy defaultPolicy) {
            this.defaultPolicy = Objects.requireNonNull(defaultPolicy, "defaultPolicy");
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

        public Builder chatClientObservationConvention(
            ChatClientObservationConvention chatClientObservationConvention) {
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

        public GuardrailsChatClientFactory build() {
            return new GuardrailsChatClientFactory(this);
        }
    }

    // ChatClient construction
    /**
     * Creates a client builder using the factory's default policy.
     *
     * @throws IllegalStateException if no default policy is configured
     */
    public ChatClient.Builder builder(ChatModel chatModel) {
        if (defaultPolicy == null) {
            throw new IllegalStateException("No default GuardrailsPolicy is configured. Supply a policy explicitly");
        }
        return builder(chatModel, defaultPolicy);
    }

    /**
     * Creates a client builder with the supplied policy.
     * Settings from the factory's default policy are not applied.
     */
    public ChatClient.Builder builder(ChatModel chatModel, GuardrailsPolicy guardrailsPolicy) {
        return builder(chatModel, guardrailsPolicy, defaultToolAdvisorBuilder);
    }

    /** Creates a client builder using the supplied policy and a copy of the tool advisor builder. */
    public ChatClient.Builder builder(ChatModel chatModel, GuardrailsPolicy guardrailsPolicy,
        ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        Objects.requireNonNull(chatModel, "chatModel");
        Objects.requireNonNull(guardrailsPolicy, "guardrailsPolicy");
        Objects.requireNonNull(toolAdvisorBuilder, "toolAdvisorBuilder");
        requireComponents(guardrailsPolicy);

        ToolCallingAdvisor.Builder<?> clientToolAdvisorBuilder = toolAdvisorBuilder.copy();

        List<ModelRequestBoundaryConfigurer> modelRequestBoundaryConfigurers = new ArrayList<>();
        if (guardrailsPolicy.toolAuthorization()) {
            ToolAuthorizationConfiguration toolAuthorizationConfiguration = Objects.requireNonNull(
                toolAuthorizationConfigurer.configure(clientToolAdvisorBuilder), "toolAuthorizationConfiguration");
            clientToolAdvisorBuilder = toolAuthorizationConfiguration.toolAdvisorBuilder();
            modelRequestBoundaryConfigurers.add(toolAuthorizationConfiguration.modelRequestBoundaryConfigurer());
        }
        if (guardrailsPolicy.privacy() != null) {
            modelRequestBoundaryConfigurers.add(createPrivacyConfigurer(
                guardrailsPolicy.privacy(), clientToolAdvisorBuilder.getAdvisorOrder()));
        }
        InspectionOutputAdvisor inspectionOutputAdvisor = null;
        if (guardrailsPolicy.inspection() != null) {
            GuardrailsPolicy.Inspection inspectionPolicy = guardrailsPolicy.inspection();
            if (inspectionPolicy.modelRequestInspectionEnabled()) {
                modelRequestBoundaryConfigurers.add(createInspectionConfigurer(inspectionPolicy));
            }
            if (inspectionPolicy.finalOutputInspectionEnabled()) {
                inspectionOutputAdvisor = new InspectionOutputAdvisor(inspectionService, inspectionPolicy.limits(),
                    inspectionObserver, inspectionPolicy.maxOutputFrames(), inspectionPolicy.outputStreamTimeout());
            }
        }
        ChatClient.Builder chatClientBuilder = createClientBuilder(chatModel, clientToolAdvisorBuilder);
        if (!modelRequestBoundaryConfigurers.isEmpty()) {
            ModelRequestBoundaryConfigurer[] configurers =
                modelRequestBoundaryConfigurers.toArray(new ModelRequestBoundaryConfigurer[0]);

            ModelRequestBoundaryConfigurer combinedConfigurer =
                ModelRequestBoundaryConfigurer.compose(configurers);

            combinedConfigurer.configure(chatClientBuilder);
        }
        if (inspectionOutputAdvisor != null) {
            chatClientBuilder.defaultAdvisors(inspectionOutputAdvisor);
        }
        return chatClientBuilder;
    }

    private ModelRequestBoundaryConfigurer createPrivacyConfigurer(GuardrailsPolicy.Privacy privacyPolicy,
        int toolAdvisorOrder) {
        PrivacyToolCallbackFactory privacyToolCallbackFactory = new PrivacyToolCallbackFactory(
            privacyService, privacyPolicy.toolDisclosurePolicy(), privacyObserver);
        PrivacyChatClientConfigurer.Builder privacyConfigurerBuilder =
            PrivacyChatClientConfigurer.builder(privacyService)
                .toolCallbackFactory(privacyToolCallbackFactory).autoWrapToolCallbacks(true)
                .responseInspectionLimits(privacyPolicy.responseLimits()).enforcementObserver(privacyObserver);
        GuardrailsPolicy.OutputProtection outputProtection = privacyPolicy.outputProtection();
        if (outputProtection != null) {
            privacyConfigurerBuilder.outputProtection(outputProtection.action(), outputProtection.blockMessage());
        }
        return privacyConfigurerBuilder.build().forToolAdvisorOrder(toolAdvisorOrder);
    }

    private ModelRequestBoundaryConfigurer createInspectionConfigurer(GuardrailsPolicy.Inspection inspectionPolicy) {
        PrivacyProcessingStatusResolver privacyProcessingStatusResolver = chatClientRequest -> {
            if (PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(chatClientRequest)) {
                return ContentSegment.PrivacyProcessingStatus.PROCESSED;
            }
            return ContentSegment.PrivacyProcessingStatus.UNKNOWN;
        };
        return new InspectionChatClientConfigurer(inspectionService,
            inspectionPolicy.limits(), privacyProcessingStatusResolver, inspectionObserver);
    }

    private ChatClient.Builder createClientBuilder(ChatModel chatModel,
        ToolCallingAdvisor.Builder<?> clientToolAdvisorBuilder) {
        ChatClient.Builder chatClientBuilder = ChatClient.builder(chatModel, observationRegistry,
            chatClientObservationConvention, advisorObservationConvention, clientToolAdvisorBuilder);
        return Objects.requireNonNull(clientCustomizer.apply(chatClientBuilder), "customized client builder");
    }

    private void requireComponents(GuardrailsPolicy guardrailsPolicy) {
        if (guardrailsPolicy.privacy() != null && privacyService == null) {
            throw new IllegalStateException("Privacy is selected but no PrivacyService is configured");
        }
        if (guardrailsPolicy.inspection() != null && inspectionService == null) {
            throw new IllegalStateException("Inspection is selected but no InspectionService is configured");
        }
        if (guardrailsPolicy.toolAuthorization() && toolAuthorizationConfigurer == null) {
            throw new IllegalStateException(
                "Tool authorization is selected but no authorization integration is configured");
        }
        validatePiiTokenSimplificationRequirements(guardrailsPolicy);
    }

    private void validatePiiTokenSimplificationRequirements(GuardrailsPolicy guardrailsPolicy) {
        GuardrailsPolicy.Inspection inspectionPolicy = guardrailsPolicy.inspection();
        if (inspectionPolicy == null || !inspectionService.requiresPiiTokenSimplifier()) {
            return;
        }
        GuardrailsPolicy.Privacy privacyPolicy = guardrailsPolicy.privacy();
        if (privacyPolicy == null) {
            throw new IllegalStateException("PII token simplification requires privacy in the client policy");
        }
        if (inspectionPolicy.finalOutputInspectionEnabled() && privacyPolicy.outputProtection() == null) {
            throw new IllegalStateException("Output PII token simplification requires privacy output protection");
        }
    }

    /** Prepares tool authorization components for a client. */
    @FunctionalInterface
    public interface ToolAuthorizationConfigurer {
        /**
         * Prepares a new tool authorization configuration for each invocation.
         * Use a copy of the supplied builder and preserve the selected tool advisor's order and subtype.
         *
         * @param toolAdvisorBuilder builder for the client's selected tool advisor
         * @return a new configuration containing the tool advisor builder and its authorization boundary configurer
         */
        ToolAuthorizationConfiguration configure(ToolCallingAdvisor.Builder<?> toolAdvisorBuilder);
    }

}
