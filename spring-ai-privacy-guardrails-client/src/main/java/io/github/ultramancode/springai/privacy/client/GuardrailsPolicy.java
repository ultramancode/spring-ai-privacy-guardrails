package io.github.ultramancode.springai.privacy.client;

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
 * Selects protection for one client. Unselected features remain disabled.
 * Explicit policies replace the factory's default policy without merging settings.
 * A null privacy or inspection setting disables that feature.
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

    /** Returns a builder with this policy's settings. Changes do not affect this policy. */
    public Builder mutate() {
        return new Builder(this);
    }

    /** Privacy protects model requests and tools. A null output action disables final output protection. */
    public record Privacy(ToolDisclosurePolicy toolDisclosurePolicy, PrivacyOutputAction outputAction,
            String outputBlockMessage, PrivacyResponseInspectionLimits responseLimits) {
        public Privacy {
            Objects.requireNonNull(toolDisclosurePolicy, "toolDisclosurePolicy");
            Objects.requireNonNull(outputBlockMessage, "outputBlockMessage");
            Objects.requireNonNull(responseLimits, "responseLimits");
        }
    }

    /** Selects inspection boundaries and their execution limits. */
    public record Inspection(boolean modelRequests, boolean applicationOutput, InspectionLimits limits,
            int maxOutputFrames, Duration outputStreamTimeout) {
        public Inspection {
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(outputStreamTimeout, "outputStreamTimeout");
            if (!modelRequests && !applicationOutput) {
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

        private Builder(GuardrailsPolicy source) {
            privacy = source.privacy();
            inspection = source.inspection();
            toolAuthorization = source.toolAuthorization();
        }

        /** Enables privacy, preserving its settings if already selected. */
        public Builder privacy() {
            return privacy(settings -> {});
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
        public Builder privacy(Consumer<PrivacyBuilder> customizer) {
            PrivacyBuilder settings = new PrivacyBuilder(privacy);
            Objects.requireNonNull(customizer, "customizer").accept(settings);
            privacy = settings.build();
            return this;
        }

        /** Enables inspection, preserving its settings if already selected. */
        public Builder inspection() {
            return inspection(settings -> {});
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
        public Builder inspection(Consumer<InspectionBuilder> customizer) {
            InspectionBuilder settings = new InspectionBuilder(inspection);
            Objects.requireNonNull(customizer, "customizer").accept(settings);
            inspection = settings.build();
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
        private ToolDisclosurePolicy disclosure = ToolDisclosurePolicy.denyAll();
        private PrivacyOutputAction outputAction;
        private String outputBlockMessage = "Response blocked by privacy guardrail.";
        private PrivacyResponseInspectionLimits responseLimits = PrivacyResponseInspectionLimits.defaults();

        private PrivacyBuilder(Privacy source) {
            if (source != null) {
                disclosure = source.toolDisclosurePolicy();
                outputAction = source.outputAction();
                outputBlockMessage = source.outputBlockMessage();
                responseLimits = source.responseLimits();
            }
        }

        public PrivacyBuilder toolDisclosurePolicy(ToolDisclosurePolicy policy) {
            disclosure = Objects.requireNonNull(policy, "toolDisclosurePolicy");
            return this;
        }

        /** Enables final output protection with TOKENIZE if no action is selected, or disables it. */
        public PrivacyBuilder outputProtection(boolean enabled) {
            if (!enabled) {
                outputAction = null;
            } else if (outputAction == null) {
                outputAction = PrivacyOutputAdvisor.DEFAULT_ACTION;
            }
            return this;
        }

        public PrivacyBuilder outputAction(PrivacyOutputAction action) {
            outputAction = Objects.requireNonNull(action, "outputAction");
            return this;
        }

        public PrivacyBuilder outputBlockMessage(String message) {
            outputBlockMessage = Objects.requireNonNull(message, "outputBlockMessage");
            return this;
        }

        public PrivacyBuilder responseLimits(PrivacyResponseInspectionLimits limits) {
            responseLimits = Objects.requireNonNull(limits, "responseLimits");
            return this;
        }

        private Privacy build() {
            return new Privacy(disclosure, outputAction, outputBlockMessage, responseLimits);
        }
    }

    public static final class InspectionBuilder {
        private boolean modelRequests = true;
        private boolean applicationOutput;
        private InspectionLimits limits = InspectionLimits.defaults();
        private int maxOutputFrames = InspectionOutputAdvisor.DEFAULT_MAX_FRAMES;
        private Duration outputStreamTimeout = InspectionOutputAdvisor.DEFAULT_STREAM_TIMEOUT;

        private InspectionBuilder(Inspection source) {
            if (source != null) {
                modelRequests = source.modelRequests();
                applicationOutput = source.applicationOutput();
                limits = source.limits();
                maxOutputFrames = source.maxOutputFrames();
                outputStreamTimeout = source.outputStreamTimeout();
            }
        }

        public InspectionBuilder modelRequests(boolean enabled) {
            modelRequests = enabled;
            return this;
        }

        public InspectionBuilder applicationOutput(boolean enabled) {
            applicationOutput = enabled;
            return this;
        }

        public InspectionBuilder limits(InspectionLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        public InspectionBuilder maxOutputFrames(int maxFrames) {
            maxOutputFrames = maxFrames;
            return this;
        }

        public InspectionBuilder outputStreamTimeout(Duration timeout) {
            outputStreamTimeout = Objects.requireNonNull(timeout, "outputStreamTimeout");
            return this;
        }

        private Inspection build() {
            return new Inspection(modelRequests, applicationOutput, limits, maxOutputFrames, outputStreamTimeout);
        }
    }
}
