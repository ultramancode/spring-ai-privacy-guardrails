package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundaryConfigurer;
import io.github.ultramancode.springai.privacy.boundary.ModelRequestBoundarySpec;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.IntFunction;

/**
 * Configures the complete privacy lifecycle and model request stage on selected clients.
 * Works with or without Spring Boot. Compose optional inspection through
 * {@link ModelRequestBoundaryConfigurer#compose}, or use the security client factory.
 */
public final class PrivacyChatClientConfigurer
        implements ModelRequestBoundaryConfigurer, IntFunction<ModelRequestBoundaryConfigurer> {
    private final PrivacyService service;
    private final PrivacyToolCallbackFactory requiredToolCallbackFactory;
    // Null means output protection is disabled.
    private final PrivacyOutputAction outputAction;
    private final String outputBlockMessage;
    private final PrivacyResponseInspectionLimits responseLimits;
    private final PrivacyEnforcementObserver enforcementObserver;
    private final WeakHashMap<ChatClient.Builder, Boolean> configuredBuilders = new WeakHashMap<>();

    /** Creates a complete privacy boundary accepting tool wrappers from the same service. */
    public PrivacyChatClientConfigurer(PrivacyService service) {
        this(new Builder(service));
    }

    /** Creates a complete privacy boundary accepting wrappers from the selected factory. */
    public PrivacyChatClientConfigurer(PrivacyService service, PrivacyToolCallbackFactory requiredToolCallbackFactory) {
        this(new Builder(service).toolCallbackFactory(requiredToolCallbackFactory));
    }

    private PrivacyChatClientConfigurer(Builder builder) {
        this.service = builder.service;
        this.requiredToolCallbackFactory = builder.requiredToolCallbackFactory;
        this.outputAction = builder.outputAction;
        this.outputBlockMessage = builder.outputBlockMessage;
        this.responseLimits = builder.responseLimits;
        this.enforcementObserver = builder.enforcementObserver;
    }

    /** Creates a programmatic configuration builder; output protection is initially disabled. */
    public static Builder builder(PrivacyService service) {
        return new Builder(service);
    }

    /** Whether the current message list was transformed by this library's privacy model stage. */
    public static boolean isModelContentProtected(ChatClientRequest request) {
        return PrivacyModelRequestStage.isModelContentProtected(request);
    }

    /**
     * Positions output/context before the selected tool order and tool-call validation after it.
     * The actual call and stream chains are validated, including request-added advisors.
     * @param toolOrder order of the client's tool advisor
     * @return a contribution creating fresh infrastructure for each selected builder
     */
    public ModelRequestBoundaryConfigurer forToolCallingAdvisorOrder(int toolOrder) {
        // Input < optional output (T-2) < tool context (T-1) < tool (T).
        // T must be at least input + 2, or input + 3 when output protection is enabled.
        long minimumToolOrder = (long) PrivacyInputAdvisor.DEFAULT_ORDER + (outputAction == null ? 2 : 3);
        // Leave room for tool-call validation (T+1) before the model request boundary.
        long maximumToolOrder = (long) ModelRequestBoundarySpec.DEFAULT_ORDER - 2;
        if (toolOrder < minimumToolOrder || toolOrder > maximumToolOrder) {
            throw new IllegalArgumentException("Privacy tool advisor order must be between " + minimumToolOrder + " and "
                    + maximumToolOrder + " to fit the input and model boundaries; received " + toolOrder);
        }
        return (clientBuilder, boundarySpec) -> contribute(clientBuilder, boundarySpec, toolOrder);
    }

    @Override
    public ModelRequestBoundaryConfigurer apply(int toolOrder) {
        return forToolCallingAdvisorOrder(toolOrder);
    }

    @Override
    public void contribute(ChatClient.Builder clientBuilder, ModelRequestBoundarySpec boundarySpec) {
        forToolCallingAdvisorOrder(ToolCallingAdvisor.DEFAULT_ORDER).contribute(clientBuilder, boundarySpec);
    }

    private void contribute(ChatClient.Builder clientBuilder, ModelRequestBoundarySpec boundarySpec, int toolOrder) {
        Objects.requireNonNull(clientBuilder, "clientBuilder");
        synchronized (configuredBuilders) {
            if (configuredBuilders.containsKey(clientBuilder)) {
                throw new IllegalStateException("PrivacyChatClientConfigurer cannot configure the same ChatClient.Builder more than once");
            }
            PrivacyModelRequestStage privacyStage =
                    new PrivacyModelRequestStage(service, requiredToolCallbackFactory, enforcementObserver);
            boundarySpec.privacy(privacyStage);
            List<Advisor> advisors = new ArrayList<>();
            advisors.add(new PrivacyLifecycleAdvisor(service));
            advisors.add(new PrivacyInputAdvisor(service));
            if (outputAction != null) {
                advisors.add(new PrivacyOutputAdvisor(service, outputAction, outputBlockMessage,
                        responseLimits, enforcementObserver, toolOrder - 2));
            }
            advisors.add(new PrivacyToolContextAdvisor(service, requiredToolCallbackFactory, toolOrder - 1));
            advisors.add(new PrivacyToolCallValidationAdvisor(service, responseLimits, toolOrder + 1));
            List<Advisor> managedPrivacyAdvisors = List.copyOf(advisors);
            advisors.add(new PrivacyAdvisorChainValidator(managedPrivacyAdvisors, privacyStage, toolOrder));
            clientBuilder.defaultAdvisors(advisors);
            configuredBuilders.put(clientBuilder, Boolean.TRUE);
        }
    }

    /** Settings shared by this configurer's clients; request state is never retained here. */
    public static final class Builder {
        private final PrivacyService service;
        private PrivacyToolCallbackFactory requiredToolCallbackFactory;
        // Remains null unless outputProtection(...) is called.
        private PrivacyOutputAction outputAction;
        private String outputBlockMessage = "Response blocked by privacy guardrail.";
        private PrivacyResponseInspectionLimits responseLimits = PrivacyResponseInspectionLimits.defaults();
        private PrivacyEnforcementObserver enforcementObserver = PrivacyEnforcementObserver.noop();

        private Builder(PrivacyService service) {
            this.service = Objects.requireNonNull(service, "service");
        }

        /** Requires every registered tool wrapper to come from this exact factory. */
        public Builder toolCallbackFactory(PrivacyToolCallbackFactory factory) {
            Objects.requireNonNull(factory, "factory");
            if (!factory.usesPrivacyService(service)) {
                throw new IllegalArgumentException("requiredToolCallbackFactory must use the same PrivacyService");
            }
            this.requiredToolCallbackFactory = factory;
            return this;
        }

        /** Enables output protection with a safe rejection message for the BLOCK action. */
        public Builder outputProtection(PrivacyOutputAction action, String blockMessage) {
            this.outputAction = Objects.requireNonNull(action, "action");
            this.outputBlockMessage = Objects.requireNonNull(blockMessage, "blockMessage");
            return this;
        }

        /** Limits model responses before tool execution and when applying output protection. */
        public Builder responseInspectionLimits(PrivacyResponseInspectionLimits limits) {
            this.responseLimits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /** Observes privacy-safe enforcement events without changing enforcement decisions. */
        public Builder enforcementObserver(PrivacyEnforcementObserver enforcementObserver) {
            this.enforcementObserver = Objects.requireNonNull(enforcementObserver, "enforcementObserver");
            return this;
        }

        public PrivacyChatClientConfigurer build() {
            return new PrivacyChatClientConfigurer(this);
        }
    }
}
