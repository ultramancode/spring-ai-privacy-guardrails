package io.github.ultramancode.springai.privacy.client;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
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
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Creates clients with an explicit protection policy and request-scoped tool protection.
 * Components are shared without changing their policies or retaining request state.
 */
public final class GuardrailsChatClientFactory {
    private final Builder settings;

    private GuardrailsChatClientFactory(Builder settings) {
        this.settings = new Builder(settings);
    }

    /** Prepares reusable components. Supplying a component does not enable its feature. */
    public static Builder builder() {
        return new Builder();
    }

    /** Uses the configured default policy. */
    public ChatClient.Builder builder(ChatModel model) {
        if (settings.defaultPolicy == null) {
            throw new IllegalStateException("No default GuardrailsPolicy is configured. Supply a policy explicitly");
        }
        return builder(model, settings.defaultPolicy);
    }

    /** Uses only the supplied policy, without merging the default policy. */
    public ChatClient.Builder builder(ChatModel model, GuardrailsPolicy policy) {
        return builder(model, policy, settings.toolAdvisorBuilder);
    }

    /** Preserves a caller-selected tool advisor builder, including Tool Search builders. */
    public ChatClient.Builder builder(ChatModel model, GuardrailsPolicy policy,
            ToolCallingAdvisor.Builder<?> toolAdvisorBuilder) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(policy, "policy");
        ToolCallingAdvisor.Builder<?> toolBuilder = Objects.requireNonNull(toolAdvisorBuilder, "toolAdvisorBuilder").copy();
        requireComponents(policy);
        List<ModelRequestBoundaryConfigurer> configurers = new ArrayList<>();
        if (policy.privacy() != null) {
            GuardrailsPolicy.Privacy privacy = policy.privacy();
            PrivacyToolCallbackFactory tools = new PrivacyToolCallbackFactory(
                    settings.privacyService, privacy.toolDisclosurePolicy(), settings.privacyObserver);
            PrivacyChatClientConfigurer.Builder privacyBuilder = PrivacyChatClientConfigurer.builder(settings.privacyService)
                    .toolCallbackFactory(tools).automaticallyWrapTools(true)
                    .responseInspectionLimits(privacy.responseLimits()).enforcementObserver(settings.privacyObserver);
            if (privacy.outputAction() != null) {
                privacyBuilder.outputProtection(privacy.outputAction(), privacy.outputBlockMessage());
            }
            configurers.add(privacyBuilder.build().forToolAdvisorOrder(toolBuilder.getAdvisorOrder()));
        }
        InspectionOutputAdvisor outputAdvisor = null;
        if (policy.inspection() != null) {
            GuardrailsPolicy.Inspection inspection = policy.inspection();
            if (inspection.modelRequests()) {
                PrivacyProcessingStatusResolver resolver = request -> {
                    if (PrivacyChatClientConfigurer.hasPrivacyProcessedMessages(request)) {
                        return ContentSegment.PrivacyProcessingStatus.PROCESSED;
                    }
                    return ContentSegment.PrivacyProcessingStatus.UNKNOWN;
                };
                configurers.add(new InspectionChatClientConfigurer(settings.inspectionService,
                        inspection.limits(), resolver, settings.inspectionObserver));
            }
            if (inspection.applicationOutput()) {
                outputAdvisor = new InspectionOutputAdvisor(settings.inspectionService, inspection.limits(),
                        settings.inspectionObserver, inspection.maxOutputFrames(), inspection.outputStreamTimeout());
            }
        }
        ModelRequestBoundaryConfigurer combined = ModelRequestBoundaryConfigurer.compose(
                configurers.toArray(ModelRequestBoundaryConfigurer[]::new));
        ChatClient.Builder client;
        if (policy.toolAuthorization()) {
            client = settings.authorizedClientBuilder.create(model, toolBuilder, combined, this::createClientBuilder);
        } else {
            client = createClientBuilder(model, toolBuilder);
            if (!configurers.isEmpty()) {
                combined.configure(client);
            }
        }
        if (outputAdvisor != null) {
            client.defaultAdvisors(outputAdvisor);
        }
        return client;
    }

    private ChatClient.Builder createClientBuilder(ChatModel model, ToolCallingAdvisor.Builder<?> toolBuilder) {
        ChatClient.Builder client = ChatClient.builder(model, settings.observationRegistry,
                settings.chatClientObservationConvention, settings.advisorObservationConvention, toolBuilder);
        return Objects.requireNonNull(settings.clientCustomizer.apply(client), "customized client builder");
    }

    private void requireComponents(GuardrailsPolicy policy) {
        if (policy.privacy() != null && settings.privacyService == null) {
            throw new IllegalStateException("Privacy is selected but no PrivacyService is configured");
        }
        if (policy.inspection() != null && settings.inspectionService == null) {
            throw new IllegalStateException("Inspection is selected but no InspectionService is configured");
        }
        if (policy.toolAuthorization() && settings.authorizedClientBuilder == null) {
            throw new IllegalStateException("Tool authorization is selected but no authorization integration is configured");
        }
        if (policy.inspection() != null && settings.inspectionService.requiresPiiTokenSimplifier()) {
            if (policy.privacy() == null) {
                throw new IllegalStateException("PII token simplification requires privacy in the client policy");
            }
            if (policy.inspection().applicationOutput() && policy.privacy().outputAction() == null) {
                throw new IllegalStateException("Output PII token simplification requires privacy output protection");
            }
        }
    }

    /** Adapter for the optional security module's managed client builder. */
    @FunctionalInterface
    public interface AuthorizedClientBuilder {
        ChatClient.Builder create(ChatModel model, ToolCallingAdvisor.Builder<?> toolAdvisorBuilder,
                ModelRequestBoundaryConfigurer configurer,
                BiFunction<ChatModel, ToolCallingAdvisor.Builder<?>, ChatClient.Builder> clientBuilderFactory);
    }

    /** Reusable engine and client infrastructure settings. */
    public static final class Builder {
        private PrivacyService privacyService;
        private InspectionService inspectionService;
        private PrivacyEnforcementObserver privacyObserver = PrivacyEnforcementObserver.noop();
        private InspectionObserver inspectionObserver = InspectionObserver.noop();
        private GuardrailsPolicy defaultPolicy;
        private ToolCallingAdvisor.Builder<?> toolAdvisorBuilder = ToolCallingAdvisor.builder();
        private AuthorizedClientBuilder authorizedClientBuilder;
        private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;
        private ChatClientObservationConvention chatClientObservationConvention;
        private AdvisorObservationConvention advisorObservationConvention;
        private UnaryOperator<ChatClient.Builder> clientCustomizer = UnaryOperator.identity();

        private Builder() {}

        private Builder(Builder source) {
            privacyService = source.privacyService;
            inspectionService = source.inspectionService;
            privacyObserver = source.privacyObserver;
            inspectionObserver = source.inspectionObserver;
            defaultPolicy = source.defaultPolicy;
            toolAdvisorBuilder = source.toolAdvisorBuilder.copy();
            authorizedClientBuilder = source.authorizedClientBuilder;
            observationRegistry = source.observationRegistry;
            chatClientObservationConvention = source.chatClientObservationConvention;
            advisorObservationConvention = source.advisorObservationConvention;
            clientCustomizer = source.clientCustomizer;
        }

        public Builder privacyService(PrivacyService service) {
            privacyService = Objects.requireNonNull(service, "privacyService");
            return this;
        }

        public Builder inspectionService(InspectionService service) {
            inspectionService = Objects.requireNonNull(service, "inspectionService");
            return this;
        }

        public Builder privacyObserver(PrivacyEnforcementObserver observer) {
            privacyObserver = Objects.requireNonNull(observer, "privacyObserver");
            return this;
        }

        public Builder inspectionObserver(InspectionObserver observer) {
            inspectionObserver = Objects.requireNonNull(observer, "inspectionObserver");
            return this;
        }

        public Builder defaultPolicy(GuardrailsPolicy policy) {
            defaultPolicy = Objects.requireNonNull(policy, "defaultPolicy");
            return this;
        }

        public Builder toolAdvisorBuilder(ToolCallingAdvisor.Builder<?> builder) {
            toolAdvisorBuilder = Objects.requireNonNull(builder, "toolAdvisorBuilder").copy();
            return this;
        }

        public Builder toolAuthorization(AuthorizedClientBuilder builder) {
            authorizedClientBuilder = Objects.requireNonNull(builder, "authorizedClientBuilder");
            return this;
        }

        public Builder observationRegistry(ObservationRegistry registry) {
            observationRegistry = Objects.requireNonNull(registry, "observationRegistry");
            return this;
        }

        public Builder chatClientObservationConvention(ChatClientObservationConvention convention) {
            chatClientObservationConvention = convention;
            return this;
        }

        public Builder advisorObservationConvention(AdvisorObservationConvention convention) {
            advisorObservationConvention = convention;
            return this;
        }

        public Builder clientCustomizer(UnaryOperator<ChatClient.Builder> customizer) {
            clientCustomizer = Objects.requireNonNull(customizer, "clientCustomizer");
            return this;
        }

        public GuardrailsChatClientFactory build() {
            return new GuardrailsChatClientFactory(this);
        }
    }
}
