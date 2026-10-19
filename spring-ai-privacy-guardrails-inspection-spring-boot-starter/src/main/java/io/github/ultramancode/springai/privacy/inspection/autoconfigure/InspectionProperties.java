package io.github.ultramancode.springai.privacy.inspection.autoconfigure;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionOutputAdvisor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Inspection policies and execution limits under {@code spring.ai.inspection}.
 * Input and output receive separate execution budgets with the same configured values.
 */
@ConfigurationProperties("spring.ai.inspection")
public class InspectionProperties {

    /** Maximum number of text segments in one input or output inspection. */
    private int maxSegments = InspectionLimits.DEFAULT_MAX_SEGMENTS;

    /** Maximum combined text length in one input or output inspection. */
    private int maxCharacters = InspectionLimits.DEFAULT_MAX_CHARACTERS;

    /** Timeout shared by inspectors and policy evaluation for one input or output inspection. */
    private Duration timeout = InspectionLimits.DEFAULT_TIMEOUT;

    /** Default policy applied to eligible operational inspection failures. */
    private InspectionFailurePolicy failurePolicy = InspectionFailurePolicy.FAIL_CLOSED;

    /** Failure policy overrides keyed by inspector ID. Unspecified inspectors use the default policy. */
    private Map<String, InspectionFailurePolicy> failurePolicyOverrides = new LinkedHashMap<>();

    /**
     * IDs of inspectors that receive text with simplified PII tokens.
     * Simplification removes the random part only from tokens issued by the current privacy session.
     * No inspectors are selected by default.
     */
    private Set<String> piiTokenSimplificationInspectors = Set.of();

    private final Output output = new Output();

    /** Output inspection settings. Input and output use the same service and configured limits. */
    public static class Output {
        /** Enables inspection of application output, including returnDirect tool results. Disabled by default. */
        private boolean enabled;
        /** Maximum response frames collected for output inspection, including empty frames. */
        private int maxFrames = InspectionOutputAdvisor.DEFAULT_MAX_FRAMES;
        /** Maximum total stream assembly duration, including model rounds and tool execution. */
        private Duration streamTimeout = InspectionOutputAdvisor.DEFAULT_STREAM_TIMEOUT;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxFrames() {
            return maxFrames;
        }

        public void setMaxFrames(int maxFrames) {
            this.maxFrames = maxFrames;
        }

        public Duration getStreamTimeout() {
            return streamTimeout;
        }

        public void setStreamTimeout(Duration streamTimeout) {
            this.streamTimeout = streamTimeout;
        }
    }

    public Output getOutput() {
        return output;
    }

    public Set<String> getPiiTokenSimplificationInspectors() {
        return piiTokenSimplificationInspectors;
    }

    public void setPiiTokenSimplificationInspectors(Set<String> piiTokenSimplificationInspectors) {
        this.piiTokenSimplificationInspectors = piiTokenSimplificationInspectors;
    }

    /**
     * Creates validated limits from the current property values.
     *
     * @return the limits for one input or output inspection
     * @throws IllegalArgumentException if a configured limit is invalid
     */
    public InspectionLimits limits() {
        return new InspectionLimits(maxSegments, maxCharacters, timeout);
    }

    public int getMaxSegments() {
        return maxSegments;
    }

    public void setMaxSegments(int maxSegments) {
        this.maxSegments = maxSegments;
    }

    public int getMaxCharacters() {
        return maxCharacters;
    }

    public void setMaxCharacters(int maxCharacters) {
        this.maxCharacters = maxCharacters;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public InspectionFailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    public void setFailurePolicy(InspectionFailurePolicy failurePolicy) {
        this.failurePolicy = failurePolicy;
    }

    public Map<String, InspectionFailurePolicy> getFailurePolicyOverrides() {
        return failurePolicyOverrides;
    }

    public void setFailurePolicyOverrides(Map<String, InspectionFailurePolicy> failurePolicyOverrides) {
        this.failurePolicyOverrides = failurePolicyOverrides;
    }
}
