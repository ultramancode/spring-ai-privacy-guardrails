package io.github.ultramancode.springai.privacy.inspection.autoconfigure;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionOutputAdvisor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Common inspection policy and execution limits. */
@ConfigurationProperties("spring.ai.inspection")
public class InspectionProperties {

    /** Enables inspection configuration for explicitly selected ChatClient instances. */
    private boolean enabled;

    /** Maximum text segments per model request or separately inspected final application response. */
    private int maxSegments = InspectionLimits.DEFAULT_MAX_SEGMENTS;

    /** Maximum combined source payload length in UTF-16 units, including JSON syntax. */
    private int maxCharacters = InspectionLimits.DEFAULT_MAX_CHARACTERS;

    /** Maximum findings across all inspectors for one input or output inspection. */
    private int maxFindings = InspectionLimits.DEFAULT_MAX_FINDINGS;

    /** Timeout shared by inspectors and policy evaluation for one input or output inspection. */
    private Duration timeout = InspectionLimits.DEFAULT_TIMEOUT;

    /** Policy applied to operational inspection failures. */
    private InspectionFailurePolicy failurePolicy = InspectionFailurePolicy.FAIL_CLOSED;

    private final Output output = new Output();

    /** Optional final application-output inspection using the common service and inspection limits. */
    public static class Output {
        /** Enables final application-facing text ALLOW/BLOCK inspection, including returnDirect. Disabled by default. */
        private boolean enabled;
        /** Maximum buffered frames and model frames across a streamed tool loop, including empty frames. */
        private int maxFrames = InspectionOutputAdvisor.DEFAULT_MAX_FRAMES;
        /** Maximum total stream assembly duration, including model rounds and tool execution. */
        private Duration streamTimeout = InspectionOutputAdvisor.DEFAULT_STREAM_TIMEOUT;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public int getMaxFrames() { return maxFrames; }
        public void setMaxFrames(int value) { maxFrames = value; }
        public Duration getStreamTimeout() { return streamTimeout; }
        public void setStreamTimeout(Duration value) { streamTimeout = value; }
    }

    public Output getOutput() { return output; }

    public InspectionLimits limits() {
        return new InspectionLimits(maxSegments, maxCharacters, maxFindings, timeout);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public int getMaxSegments() {
        return maxSegments;
    }

    public void setMaxSegments(int value) {
        maxSegments = value;
    }

    public int getMaxCharacters() {
        return maxCharacters;
    }

    public void setMaxCharacters(int value) {
        maxCharacters = value;
    }

    public int getMaxFindings() {
        return maxFindings;
    }

    public void setMaxFindings(int value) {
        maxFindings = value;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration value) {
        timeout = value;
    }

    public InspectionFailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    public void setFailurePolicy(InspectionFailurePolicy value) {
        failurePolicy = value;
    }
}
