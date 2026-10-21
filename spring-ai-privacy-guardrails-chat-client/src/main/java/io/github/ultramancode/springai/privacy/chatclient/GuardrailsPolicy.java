package io.github.ultramancode.springai.privacy.chatclient;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionOutputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAction;
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
     * @param outputProtection final output protection settings, or null to disable final output protection
     * @param responseLimits limits for inspecting model responses and buffering output
     */
    public record Privacy(ToolDisclosurePolicy toolDisclosurePolicy, OutputProtection outputProtection,
        PrivacyResponseInspectionLimits responseLimits) {
        public Privacy {
            Objects.requireNonNull(toolDisclosurePolicy, "toolDisclosurePolicy");
            Objects.requireNonNull(responseLimits, "responseLimits");
        }

        /** Creates a builder with default privacy settings. */
        public static PrivacyBuilder builder() {
            return new PrivacyBuilder(null);
        }

        /** Returns a new builder initialized with these privacy settings. */
        public PrivacyBuilder mutate() {
            return new PrivacyBuilder(this);
        }
    }

    /**
     * Settings for protecting final output.
     *
     * @param action action applied when final output contains PII
     * @param blockMessage non-blank message used when BLOCK rejects the response
     */
    public record OutputProtection(PrivacyOutputAction action, String blockMessage) {
        public OutputProtection {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(blockMessage, "blockMessage");
            if (blockMessage.isBlank()) {
                throw new IllegalArgumentException("blockMessage must not be blank");
            }
        }
    }

    /**
     * Selects inspection boundaries and their execution limits.
     *
     * @param modelRequestInspectionEnabled whether to inspect each model request,
     *                                      including requests after tool execution
     * @param finalOutputInspectionEnabled whether to inspect the final output returned by ChatClient to its caller
     * @param limits execution limits for each inspection
     * @param maxOutputFrames maximum response frames buffered for streaming output inspection
     * @param outputStreamTimeout maximum time to collect streaming output for inspection
     */
    public record Inspection(boolean modelRequestInspectionEnabled, boolean finalOutputInspectionEnabled,
        InspectionLimits limits, int maxOutputFrames, Duration outputStreamTimeout) {
        public Inspection {
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(outputStreamTimeout, "outputStreamTimeout");
            if (!modelRequestInspectionEnabled && !finalOutputInspectionEnabled) {
                throw new IllegalArgumentException("Select model request or final output inspection");
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

        /** Creates a builder with default inspection settings. */
        public static InspectionBuilder builder() {
            return new InspectionBuilder(null);
        }

        /** Returns a new builder initialized with these inspection settings. */
        public InspectionBuilder mutate() {
            return new InspectionBuilder(this);
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
            if (privacy == null) {
                privacy = Privacy.builder().build();
            }
            return this;
        }

        /** Selects or removes privacy. Selecting it preserves existing settings. */
        public Builder privacy(boolean enabled) {
            if (enabled) {
                return privacy();
            }
            privacy = null;
            return this;
        }

        /** Uses the supplied privacy settings, replacing any previous privacy settings. */
        public Builder privacy(Privacy privacyPolicy) {
            privacy = Objects.requireNonNull(privacyPolicy, "privacyPolicy");
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
            if (inspection == null) {
                inspection = Inspection.builder().build();
            }
            return this;
        }

        /** Selects or removes inspection. Selecting it preserves existing settings. */
        public Builder inspection(boolean enabled) {
            if (enabled) {
                return inspection();
            }
            inspection = null;
            return this;
        }

        /** Uses the supplied inspection settings, replacing any previous inspection settings. */
        public Builder inspection(Inspection inspectionPolicy) {
            inspection = Objects.requireNonNull(inspectionPolicy, "inspectionPolicy");
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
        private OutputProtection outputProtection;
        private PrivacyResponseInspectionLimits responseLimits = PrivacyResponseInspectionLimits.defaults();

        private PrivacyBuilder(Privacy privacyPolicy) {
            if (privacyPolicy != null) {
                toolDisclosurePolicy = privacyPolicy.toolDisclosurePolicy();
                outputProtection = privacyPolicy.outputProtection();
                responseLimits = privacyPolicy.responseLimits();
            }
        }

        public PrivacyBuilder toolDisclosurePolicy(ToolDisclosurePolicy toolDisclosurePolicy) {
            this.toolDisclosurePolicy = Objects.requireNonNull(toolDisclosurePolicy, "toolDisclosurePolicy");
            return this;
        }

        /** Returns a configurer for final output protection. */
        public OutputProtectionConfigurer outputProtection() {
            return new OutputProtectionConfigurer(this);
        }

        /** Enables final output protection with the supplied settings, replacing any previous settings. */
        public PrivacyBuilder outputProtection(OutputProtection outputProtection) {
            this.outputProtection = Objects.requireNonNull(outputProtection, "outputProtection");
            return this;
        }

        public PrivacyBuilder responseLimits(PrivacyResponseInspectionLimits responseLimits) {
            this.responseLimits = Objects.requireNonNull(responseLimits, "responseLimits");
            return this;
        }

        /** Creates privacy settings from this builder. */
        public Privacy build() {
            return new Privacy(toolDisclosurePolicy, outputProtection, responseLimits);
        }
    }

    /** Selects or disables final output protection. */
    public static final class OutputProtectionConfigurer {
        private static final String DEFAULT_BLOCK_MESSAGE = "Response blocked by privacy guardrail.";

        private final PrivacyBuilder privacyPolicyBuilder;

        private OutputProtectionConfigurer(PrivacyBuilder privacyPolicyBuilder) {
            this.privacyPolicyBuilder = privacyPolicyBuilder;
        }

        /** Enables final output protection with TOKENIZE. */
        public PrivacyBuilder tokenize() {
            return privacyPolicyBuilder.outputProtection(
                new OutputProtection(PrivacyOutputAction.TOKENIZE, DEFAULT_BLOCK_MESSAGE));
        }

        /** Enables final output protection with REDACT. */
        public PrivacyBuilder redact() {
            return privacyPolicyBuilder.outputProtection(
                new OutputProtection(PrivacyOutputAction.REDACT, DEFAULT_BLOCK_MESSAGE));
        }

        /** Enables BLOCK output protection with the default block message. */
        public PrivacyBuilder block() {
            return block(DEFAULT_BLOCK_MESSAGE);
        }

        /** Enables BLOCK output protection with the supplied block message. */
        public PrivacyBuilder block(String blockMessage) {
            return privacyPolicyBuilder.outputProtection(new OutputProtection(PrivacyOutputAction.BLOCK, blockMessage));
        }

        /** Removes final output protection settings. */
        public PrivacyBuilder disable() {
            privacyPolicyBuilder.outputProtection = null;
            return privacyPolicyBuilder;
        }
    }

    public static final class InspectionBuilder {
        private boolean modelRequestInspectionEnabled = true;
        private boolean finalOutputInspectionEnabled;
        private InspectionLimits limits = InspectionLimits.defaults();
        private int maxOutputFrames = InspectionOutputAdvisor.DEFAULT_MAX_FRAMES;
        private Duration outputStreamTimeout = InspectionOutputAdvisor.DEFAULT_STREAM_TIMEOUT;

        private InspectionBuilder(Inspection inspectionPolicy) {
            if (inspectionPolicy != null) {
                modelRequestInspectionEnabled = inspectionPolicy.modelRequestInspectionEnabled();
                finalOutputInspectionEnabled = inspectionPolicy.finalOutputInspectionEnabled();
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

        /** Sets whether to inspect the final output returned by ChatClient to its caller. */
        public InspectionBuilder finalOutputInspectionEnabled(boolean enabled) {
            finalOutputInspectionEnabled = enabled;
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

        /** Creates inspection settings from this builder. */
        public Inspection build() {
            return new Inspection(modelRequestInspectionEnabled, finalOutputInspectionEnabled, limits,
                maxOutputFrames, outputStreamTimeout);
        }
    }
}
