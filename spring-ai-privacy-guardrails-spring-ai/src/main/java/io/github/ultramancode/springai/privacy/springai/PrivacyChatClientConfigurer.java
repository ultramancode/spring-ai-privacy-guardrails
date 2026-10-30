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
 * Configures privacy processing and request sessions on a Spring AI client builder.
 * Compose other model-request features through {@link ModelRequestBoundaryConfigurer#compose}.
 */
public final class PrivacyChatClientConfigurer
        implements ModelRequestBoundaryConfigurer, IntFunction<ModelRequestBoundaryConfigurer> {
    private final PrivacyService privacyService;
    private final PrivacyToolCallbackFactory expectedToolCallbackFactory;
    private final boolean autoWrapToolCallbacksEnabled;
    // Null means output protection is disabled.
    private final PrivacyOutputAction outputAction;
    private final String outputBlockMessage;
    private final boolean simplifyOutputTokens;
    private final PrivacyResponseInspectionLimits responseInspectionLimits;
    private final PrivacyEnforcementObserver enforcementObserver;
    private final WeakHashMap<ChatClient.Builder, Boolean> configuredBuilders = new WeakHashMap<>();

    /** Creates a complete privacy boundary accepting tool wrappers from the same service. */
    public PrivacyChatClientConfigurer(PrivacyService privacyService) {
        this(new Builder(privacyService));
    }

    /**
     * Creates a complete privacy boundary accepting wrappers from the selected factory.
     *
     * @see Builder#toolCallbackFactory(PrivacyToolCallbackFactory)
     */
    public PrivacyChatClientConfigurer(PrivacyService privacyService, PrivacyToolCallbackFactory expectedToolCallbackFactory) {
        this(new Builder(privacyService).toolCallbackFactory(expectedToolCallbackFactory));
    }

    private PrivacyChatClientConfigurer(Builder privacyConfigurerBuilder) {
        this.privacyService = privacyConfigurerBuilder.privacyService;
        this.expectedToolCallbackFactory = privacyConfigurerBuilder.expectedToolCallbackFactory;
        this.autoWrapToolCallbacksEnabled = privacyConfigurerBuilder.autoWrapToolCallbacksEnabled;
        this.outputAction = privacyConfigurerBuilder.outputAction;
        this.outputBlockMessage = privacyConfigurerBuilder.outputBlockMessage;
        this.simplifyOutputTokens = privacyConfigurerBuilder.simplifyOutputTokens;
        this.responseInspectionLimits = privacyConfigurerBuilder.responseInspectionLimits;
        this.enforcementObserver = privacyConfigurerBuilder.enforcementObserver;
    }

    /** Creates a programmatic configuration builder. Output protection is initially disabled. */
    public static Builder builder(PrivacyService privacyService) {
        return new Builder(privacyService);
    }

    /**
     * Reports whether the current prompt messages have passed this library's privacy model stage.
     *
     * <p>The result indicates that privacy processing completed. It does not report
     * whether personal data was found or message text changed. If no personal data
     * was found, the text may remain unchanged while this method returns {@code true}.</p>
     *
     * @param chatClientRequest the model request whose prompt messages are checked
     * @return {@code true} if the request carries this library's privacy processing marker
     *         and its current message list matches the recorded list
     */
    public static boolean hasPrivacyProcessedMessages(ChatClientRequest chatClientRequest) {
        return PrivacyModelRequestStage.hasPrivacyProcessedMessages(chatClientRequest);
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
        return (chatClientBuilder, boundarySpec) -> contributeToBoundary(chatClientBuilder, boundarySpec, toolOrder);
    }

    @Override
    public ModelRequestBoundaryConfigurer apply(int toolOrder) {
        return forToolAdvisorOrder(toolOrder);
    }

    @Override
    public void contributeToBoundary(ChatClient.Builder chatClientBuilder, ModelRequestBoundarySpec boundarySpec) {
        forToolAdvisorOrder(ToolCallingAdvisor.DEFAULT_ORDER).contributeToBoundary(chatClientBuilder, boundarySpec);
    }

    /**
     * Registers the privacy stage and supporting advisors.
     *
     * @see #forToolAdvisorOrder(int)
     */
    private void contributeToBoundary(ChatClient.Builder chatClientBuilder, ModelRequestBoundarySpec boundarySpec, int toolOrder) {
        Objects.requireNonNull(chatClientBuilder, "chatClientBuilder");
        synchronized (configuredBuilders) {
            if (configuredBuilders.containsKey(chatClientBuilder)) {
                throw new IllegalStateException("PrivacyChatClientConfigurer cannot configure the same ChatClient.Builder more than once");
            }
            PrivacyModelRequestStage privacyStage =
                new PrivacyModelRequestStage(privacyService, expectedToolCallbackFactory, enforcementObserver);
            boundarySpec.privacy(privacyStage);
            List<Advisor> advisors = new ArrayList<>();
            PrivacyToolCallbackFactory autoWrappingToolCallbackFactory = null;
            if (autoWrapToolCallbacksEnabled) {
                autoWrappingToolCallbackFactory = expectedToolCallbackFactory;
            }
            advisors.add(new PrivacyLifecycleAdvisor(privacyService, autoWrappingToolCallbackFactory));
            advisors.add(new PrivacyInputAdvisor(privacyService));
            if (outputAction != null) {
                advisors.add(new PrivacyOutputAdvisor(privacyService, outputAction, outputBlockMessage,
                    responseInspectionLimits, enforcementObserver, toolOrder - 2, simplifyOutputTokens));
            }
            advisors.add(new PrivacyToolContextAdvisor(privacyService, expectedToolCallbackFactory, toolOrder - 1));
            advisors.add(new PrivacyToolCallValidationAdvisor(privacyService, responseInspectionLimits, toolOrder + 1));
            List<Advisor> managedPrivacyAdvisors = List.copyOf(advisors);
            advisors.add(new PrivacyAdvisorChainValidator(managedPrivacyAdvisors, privacyStage, toolOrder));
            chatClientBuilder.defaultAdvisors(advisors);
            configuredBuilders.put(chatClientBuilder, Boolean.TRUE);
        }
    }

    /** Settings shared by this configurer's clients. Request state is never retained here. */
    public static final class Builder {
        private final PrivacyService privacyService;
        private PrivacyToolCallbackFactory expectedToolCallbackFactory;
        private boolean autoWrapToolCallbacksEnabled;
        // Remains null unless outputProtection(...) is called.
        private PrivacyOutputAction outputAction;
        private String outputBlockMessage = "Response blocked by privacy guardrail.";
        private boolean simplifyOutputTokens;
        private PrivacyResponseInspectionLimits responseInspectionLimits = PrivacyResponseInspectionLimits.defaults();
        private PrivacyEnforcementObserver enforcementObserver = PrivacyEnforcementObserver.noop();

        private Builder(PrivacyService privacyService) {
            this.privacyService = Objects.requireNonNull(privacyService, "privacyService");
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
         * @param privacyToolCallbackFactory factory whose tool wrappers the configured clients must use
         * @return this builder
         */
        public Builder toolCallbackFactory(PrivacyToolCallbackFactory privacyToolCallbackFactory) {
            Objects.requireNonNull(privacyToolCallbackFactory, "privacyToolCallbackFactory");
            if (!privacyToolCallbackFactory.usesSamePrivacyService(privacyService)) {
                throw new IllegalArgumentException("privacyToolCallbackFactory must use the same PrivacyService");
            }
            this.expectedToolCallbackFactory = privacyToolCallbackFactory;
            return this;
        }

        /**
         * Sets whether each request's resolved tool callbacks are wrapped automatically.
         * Disabled by default. Enabling requires a factory selected through
         * {@link #toolCallbackFactory(PrivacyToolCallbackFactory)}.
         *
         * @param autoWrapToolCallbacksEnabled whether to wrap tool callbacks automatically
         * @return this builder
         */
        public Builder autoWrapToolCallbacks(boolean autoWrapToolCallbacksEnabled) {
            this.autoWrapToolCallbacksEnabled = autoWrapToolCallbacksEnabled;
            return this;
        }

        /** Enables output protection with a safe rejection message for the BLOCK action. */
        public Builder outputProtection(PrivacyOutputAction outputAction, String outputBlockMessage) {
            return outputProtection(outputAction, outputBlockMessage, false);
        }

        /**
         * Enables output protection and optional token simplification in the final response body.
         * Simplified markers retain entity types and indexes but cannot restore original values.
         * Model requests, tool arguments and response metadata are not simplified.
         */
        public Builder outputProtection(
                PrivacyOutputAction outputAction, String outputBlockMessage, boolean simplifyOutputTokens) {
            this.outputAction = Objects.requireNonNull(outputAction, "outputAction");
            this.outputBlockMessage = Objects.requireNonNull(outputBlockMessage, "outputBlockMessage");
            this.simplifyOutputTokens = simplifyOutputTokens;
            return this;
        }

        /** Limits model responses before tool execution and when applying output protection. */
        public Builder responseInspectionLimits(PrivacyResponseInspectionLimits responseInspectionLimits) {
            this.responseInspectionLimits = Objects.requireNonNull(responseInspectionLimits, "responseInspectionLimits");
            return this;
        }

        /** Observes privacy-safe enforcement events without changing enforcement decisions. */
        public Builder enforcementObserver(PrivacyEnforcementObserver enforcementObserver) {
            this.enforcementObserver = Objects.requireNonNull(enforcementObserver, "enforcementObserver");
            return this;
        }

        public PrivacyChatClientConfigurer build() {
            if (autoWrapToolCallbacksEnabled && expectedToolCallbackFactory == null) {
                throw new IllegalStateException("Automatic tool protection requires a tool callback factory");
            }
            return new PrivacyChatClientConfigurer(this);
        }
    }
}
