package io.github.ultramancode.springai.privacy.chatclient;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionOutputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAction;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyResponseInspectionLimits;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Selects privacy protection, content inspection and tool authorization for a client.
 * At least one feature must be enabled.
 *
 * @param privacy privacy settings, or null to disable privacy protection
 * @param inspection inspection settings, or null to disable content inspection
 * @param toolAuthorization whether to enable tool authorization
 */
public record GuardrailsPolicy(Privacy privacy, Inspection inspection, boolean toolAuthorization) {
    public GuardrailsPolicy {
        if (privacy == null && inspection == null && !toolAuthorization) {
            throw new IllegalArgumentException("Select at least one guardrails feature");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Returns a new builder initialized with this policy's settings. Builder changes do not modify this policy. */
    public Builder mutate() {
        return new Builder(this);
    }

    /**
     * Settings for protecting model requests and tools, with optional final output protection.
     *
     * @param toolDisclosurePolicy which PII entity types each tool may receive as original values
     * @param outputProtectionEnabled whether to protect final output
     * @param outputAction action to apply when final output protection is enabled
     * @param outputBlockMessage message used when BLOCK rejects the response
     * @param responseLimits limits for inspecting model responses and buffering output
     */
    public record Privacy(ToolDisclosurePolicy toolDisclosurePolicy, boolean outputProtectionEnabled,
        PrivacyOutputAction outputAction, String outputBlockMessage, PrivacyResponseInspectionLimits responseLimits) {
        public Privacy {
            Objects.requireNonNull(toolDisclosurePolicy, "toolDisclosurePolicy");
            Objects.requireNonNull(outputAction, "outputAction");
            Objects.requireNonNull(outputBlockMessage, "outputBlockMessage");
            Objects.requireNonNull(responseLimits, "responseLimits");
        }
    }

    /**
     * Selects inspection boundaries and their execution limits.
     *
     * @param modelRequestInspectionEnabled whether to inspect each model request,
     *                                      including requests after tool execution
     * @param applicationOutputInspectionEnabled whether to inspect the output returned by ChatClient to its caller
     * @param limits execution limits for each inspection
     * @param maxOutputFrames maximum response frames buffered for streaming output inspection
     * @param outputStreamTimeout maximum time to collect streaming output for inspection
     */
    public record Inspection(boolean modelRequestInspectionEnabled, boolean applicationOutputInspectionEnabled,
        InspectionLimits limits, int maxOutputFrames, Duration outputStreamTimeout) {
        public Inspection {
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(outputStreamTimeout, "outputStreamTimeout");
            if (!modelRequestInspectionEnabled && !applicationOutputInspectionEnabled) {
                throw new IllegalArgumentException("Select model request or application output inspection");
            }
            if (maxOutputFrames < 1 || outputStreamTimeout.isZero() || outputStreamTimeout.isNegative()) {
                throw new IllegalArgumentException("Output inspection limits must be positive");
            }
            try {
                outputStreamTimeout.toNanos();
            } catch (ArithmeticException ex) {
                throw new IllegalArgumentException("Output stream timeout must fit nanoseconds");
            }
        }
    }

    public static final class Builder {
        private Privacy privacy;
        private Inspection inspection;
        private boolean toolAuthorization;

        private Builder() {}

        private Builder(GuardrailsPolicy guardrailsPolicy) {
            privacy = guardrailsPolicy.privacy();
            inspection = guardrailsPolicy.inspection();
            toolAuthorization = guardrailsPolicy.toolAuthorization();
        }

        /** Enables privacy, preserving its settings if already selected. */
        public Builder privacy() {
            return privacy(privacyPolicyBuilder -> {});
        }

        /** Selects or removes privacy. Selecting it preserves existing settings. */
        public Builder privacy(boolean enabled) {
            if (enabled) {
                return privacy();
            }
            privacy = null;
            return this;
        }

        /** Customizes privacy while preserving settings that are not changed. */
        public Builder privacy(Consumer<PrivacyBuilder> privacyPolicyCustomizer) {
            PrivacyBuilder privacyPolicyBuilder = new PrivacyBuilder(privacy);
            Objects.requireNonNull(privacyPolicyCustomizer, "privacyPolicyCustomizer").accept(privacyPolicyBuilder);
            privacy = privacyPolicyBuilder.build();
            return this;
        }

        /** Enables inspection, preserving its settings if already selected. */
        public Builder inspection() {
            return inspection(inspectionPolicyBuilder -> {});
        }

        /** Selects or removes inspection. Selecting it preserves existing settings. */
        public Builder inspection(boolean enabled) {
            if (enabled) {
                return inspection();
            }
            inspection = null;
            return this;
        }

        /** Customizes inspection while preserving settings that are not changed. */
        public Builder inspection(Consumer<InspectionBuilder> inspectionPolicyCustomizer) {
            InspectionBuilder inspectionPolicyBuilder = new InspectionBuilder(inspection);
            Objects.requireNonNull(inspectionPolicyCustomizer, "inspectionPolicyCustomizer").accept(inspectionPolicyBuilder);
            inspection = inspectionPolicyBuilder.build();
            return this;
        }

        public Builder toolAuthorization(boolean enabled) {
            toolAuthorization = enabled;
            return this;
        }

        public GuardrailsPolicy build() {
            return new GuardrailsPolicy(privacy, inspection, toolAuthorization);
        }
    }

    public static final class PrivacyBuilder {
        private ToolDisclosurePolicy toolDisclosurePolicy = ToolDisclosurePolicy.denyAll();
        private boolean outputProtectionEnabled;
        private PrivacyOutputAction outputAction = PrivacyOutputAdvisor.DEFAULT_ACTION;
        private String outputBlockMessage = "Response blocked by privacy guardrail.";
        private PrivacyResponseInspectionLimits responseLimits = PrivacyResponseInspectionLimits.defaults();

        private PrivacyBuilder(Privacy privacyPolicy) {
            if (privacyPolicy != null) {
                toolDisclosurePolicy = privacyPolicy.toolDisclosurePolicy();
                outputProtectionEnabled = privacyPolicy.outputProtectionEnabled();
                outputAction = privacyPolicy.outputAction();
                outputBlockMessage = privacyPolicy.outputBlockMessage();
                responseLimits = privacyPolicy.responseLimits();
            }
        }

        public PrivacyBuilder toolDisclosurePolicy(ToolDisclosurePolicy toolDisclosurePolicy) {
            this.toolDisclosurePolicy = Objects.requireNonNull(toolDisclosurePolicy, "toolDisclosurePolicy");
            return this;
        }

        /** Enables or disables final output protection, preserving the selected action. Disabled by default. */
        public PrivacyBuilder outputProtection(boolean enabled) {
            outputProtectionEnabled = enabled;
            return this;
        }

        /** Selects the action and enables final output protection. The default action is TOKENIZE. */
        public PrivacyBuilder outputAction(PrivacyOutputAction outputAction) {
            this.outputAction = Objects.requireNonNull(outputAction, "outputAction");
            outputProtectionEnabled = true;
            return this;
        }

        public PrivacyBuilder outputBlockMessage(String outputBlockMessage) {
            this.outputBlockMessage = Objects.requireNonNull(outputBlockMessage, "outputBlockMessage");
            return this;
        }

        public PrivacyBuilder responseLimits(PrivacyResponseInspectionLimits responseLimits) {
            this.responseLimits = Objects.requireNonNull(responseLimits, "responseLimits");
            return this;
        }

        private Privacy build() {
            return new Privacy(toolDisclosurePolicy, outputProtectionEnabled, outputAction, outputBlockMessage, responseLimits);
        }
    }

    public static final class InspectionBuilder {
        private boolean modelRequestInspectionEnabled = true;
        private boolean applicationOutputInspectionEnabled;
        private InspectionLimits limits = InspectionLimits.defaults();
        private int maxOutputFrames = InspectionOutputAdvisor.DEFAULT_MAX_FRAMES;
        private Duration outputStreamTimeout = InspectionOutputAdvisor.DEFAULT_STREAM_TIMEOUT;

        private InspectionBuilder(Inspection inspectionPolicy) {
            if (inspectionPolicy != null) {
                modelRequestInspectionEnabled = inspectionPolicy.modelRequestInspectionEnabled();
                applicationOutputInspectionEnabled = inspectionPolicy.applicationOutputInspectionEnabled();
                limits = inspectionPolicy.limits();
                maxOutputFrames = inspectionPolicy.maxOutputFrames();
                outputStreamTimeout = inspectionPolicy.outputStreamTimeout();
            }
        }

        /** Sets whether to inspect each model request, including requests after tool execution. */
        public InspectionBuilder modelRequestInspectionEnabled(boolean enabled) {
            modelRequestInspectionEnabled = enabled;
            return this;
        }

        /** Sets whether to inspect the output returned by ChatClient to its caller. */
        public InspectionBuilder applicationOutputInspectionEnabled(boolean enabled) {
            applicationOutputInspectionEnabled = enabled;
            return this;
        }

        public InspectionBuilder limits(InspectionLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        public InspectionBuilder maxOutputFrames(int maxOutputFrames) {
            this.maxOutputFrames = maxOutputFrames;
            return this;
        }

        public InspectionBuilder outputStreamTimeout(Duration outputStreamTimeout) {
            this.outputStreamTimeout = Objects.requireNonNull(outputStreamTimeout, "outputStreamTimeout");
            return this;
        }

        private Inspection build() {
            return new Inspection(modelRequestInspectionEnabled, applicationOutputInspectionEnabled, limits,
                maxOutputFrames, outputStreamTimeout);
        }
    }
}
