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
    private final PrivacyToolCallbackFactory expectedToolCallbackFactory;
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

    /**
     * Creates a complete privacy boundary accepting wrappers from the selected factory.
     *
     * @see Builder#toolCallbackFactory(PrivacyToolCallbackFactory)
     */
    public PrivacyChatClientConfigurer(PrivacyService service, PrivacyToolCallbackFactory expectedToolCallbackFactory) {
        this(new Builder(service).toolCallbackFactory(expectedToolCallbackFactory));
    }

    private PrivacyChatClientConfigurer(Builder builder) {
        this.service = builder.service;
        this.expectedToolCallbackFactory = builder.expectedToolCallbackFactory;
        this.outputAction = builder.outputAction;
        this.outputBlockMessage = builder.outputBlockMessage;
        this.responseLimits = builder.responseLimits;
        this.enforcementObserver = builder.enforcementObserver;
    }

    /** Creates a programmatic configuration builder. Output protection is initially disabled. */
    public static Builder builder(PrivacyService service) {
        return new Builder(service);
    }

    /**
     * Reports whether the current prompt messages have passed this library's privacy model stage.
     *
     * <p>The result indicates that privacy processing completed. It does not report
     * whether personal data was found or message text changed. If no personal data
     * was found, the text may remain unchanged while this method returns {@code true}.</p>
     *
     * @param request the model request whose prompt messages are checked
     * @return {@code true} if the request carries this library's privacy processing marker
     *         and its current message list matches the recorded list
     */
    public static boolean hasPrivacyProcessedMessages(ChatClientRequest request) {
        return PrivacyModelRequestStage.hasPrivacyProcessedMessages(request);
    }

    /**
     * Creates a privacy configurer for the supplied tool advisor order.
     *
     * <p>In request order, the tool-context advisor runs before the tool advisor,
     * and the tool-call validation advisor runs after it. When output protection
     * is enabled, the output advisor runs before tool context. The tool advisor's
     * order is not changed.</p>
     *
     * <p>Applying the returned configurer to a client builder creates fresh privacy
     * advisors and a privacy model request stage. The actual advisor layout is
     * validated for each call or stream subscription, including advisors added
     * to individual requests.</p>
     *
     * @param toolOrder the planned order of the client's tool advisor
     * @return a configurer that registers privacy protection for that tool order
     * @throws IllegalArgumentException if the tool order leaves insufficient room
     *         for the privacy advisors between the input and model request boundaries
     */
    public ModelRequestBoundaryConfigurer forToolAdvisorOrder(int toolOrder) {
        // Input < optional output (T-2) < tool context (T-1) < tool (T).
        // T must be at least input + 2, or input + 3 when output protection is enabled.
        long minimumToolOrder = (long) PrivacyInputAdvisor.DEFAULT_ORDER + (outputAction == null ? 2 : 3);
        // Leave room for tool-call validation (T+1) before the model request boundary.
        // Responses return in reverse advisor order, so validation runs
        // before the tool advisor executes the model's tool calls.
        long maximumToolOrder = (long) ModelRequestBoundarySpec.DEFAULT_ORDER - 2;
        if (toolOrder < minimumToolOrder || toolOrder > maximumToolOrder) {
            throw new IllegalArgumentException("Privacy tool advisor order must be between " + minimumToolOrder + " and "
                    + maximumToolOrder + " to fit the input and model boundaries; received " + toolOrder);
        }
        return (clientBuilder, boundarySpec) -> contributeToBoundary(clientBuilder, boundarySpec, toolOrder);
    }

    @Override
    public ModelRequestBoundaryConfigurer apply(int toolOrder) {
        return forToolAdvisorOrder(toolOrder);
    }

    @Override
    public void contributeToBoundary(ChatClient.Builder clientBuilder, ModelRequestBoundarySpec boundarySpec) {
        forToolAdvisorOrder(ToolCallingAdvisor.DEFAULT_ORDER).contributeToBoundary(clientBuilder, boundarySpec);
    }

    /**
     * Registers the privacy stage and supporting advisors.
     *
     * @see #forToolAdvisorOrder(int)
     */
    private void contributeToBoundary(ChatClient.Builder clientBuilder, ModelRequestBoundarySpec boundarySpec, int toolOrder) {
        Objects.requireNonNull(clientBuilder, "clientBuilder");
        synchronized (configuredBuilders) {
            if (configuredBuilders.containsKey(clientBuilder)) {
                throw new IllegalStateException("PrivacyChatClientConfigurer cannot configure the same ChatClient.Builder more than once");
            }
            PrivacyModelRequestStage privacyStage =
                    new PrivacyModelRequestStage(service, expectedToolCallbackFactory, enforcementObserver);
            boundarySpec.privacy(privacyStage);
            List<Advisor> advisors = new ArrayList<>();
            advisors.add(new PrivacyLifecycleAdvisor(service));
            advisors.add(new PrivacyInputAdvisor(service));
            if (outputAction != null) {
                advisors.add(new PrivacyOutputAdvisor(service, outputAction, outputBlockMessage,
                        responseLimits, enforcementObserver, toolOrder - 2));
            }
            advisors.add(new PrivacyToolContextAdvisor(service, expectedToolCallbackFactory, toolOrder - 1));
            advisors.add(new PrivacyToolCallValidationAdvisor(service, responseLimits, toolOrder + 1));
            List<Advisor> managedPrivacyAdvisors = List.copyOf(advisors);
            advisors.add(new PrivacyAdvisorChainValidator(managedPrivacyAdvisors, privacyStage, toolOrder));
            clientBuilder.defaultAdvisors(advisors);
            configuredBuilders.put(clientBuilder, Boolean.TRUE);
        }
    }

    /** Settings shared by this configurer's clients. Request state is never retained here. */
    public static final class Builder {
        private final PrivacyService service;
        private PrivacyToolCallbackFactory expectedToolCallbackFactory;
        // Remains null unless outputProtection(...) is called.
        private PrivacyOutputAction outputAction;
        private String outputBlockMessage = "Response blocked by privacy guardrail.";
        private PrivacyResponseInspectionLimits responseLimits = PrivacyResponseInspectionLimits.defaults();
        private PrivacyEnforcementObserver enforcementObserver = PrivacyEnforcementObserver.noop();

        private Builder(PrivacyService service) {
            this.service = Objects.requireNonNull(service, "service");
        }

        /**
         * Restricts accepted tool wrappers to those created by this exact factory instance.
         * The factory must use the same {@link PrivacyService} instance as this builder.
         *
         * <p>This prevents accepting wrappers from another factory that may use a
         * different disclosure policy. It does not wrap or register tool callbacks.</p>
         *
         * <p>If no factory is selected, wrappers using the same privacy service are accepted.</p>
         *
         * @param factory factory whose tool wrappers the configured clients must use
         * @return this builder
         */
        public Builder toolCallbackFactory(PrivacyToolCallbackFactory factory) {
            Objects.requireNonNull(factory, "factory");
            if (!factory.usesPrivacyService(service)) {
                throw new IllegalArgumentException("expectedToolCallbackFactory must use the same PrivacyService");
            }
            this.expectedToolCallbackFactory = factory;
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
