package io.github.ultramancode.springai.privacy.client;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionOutputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAction;
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

        /** Enables model and tool privacy without disclosing originals or protecting final output. */
        public Builder privacy() {
            return privacy(settings -> {});
        }

        public Builder privacy(Consumer<PrivacyBuilder> customizer) {
            PrivacyBuilder settings = new PrivacyBuilder();
            customizer.accept(settings);
            privacy = settings.build();
            return this;
        }

        /** Enables inspection before each model request. */
        public Builder inspection() {
            return inspection(settings -> {});
        }

        public Builder inspection(Consumer<InspectionBuilder> customizer) {
            InspectionBuilder settings = new InspectionBuilder();
            customizer.accept(settings);
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

        public PrivacyBuilder toolDisclosurePolicy(ToolDisclosurePolicy policy) {
            disclosure = Objects.requireNonNull(policy, "toolDisclosurePolicy");
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
