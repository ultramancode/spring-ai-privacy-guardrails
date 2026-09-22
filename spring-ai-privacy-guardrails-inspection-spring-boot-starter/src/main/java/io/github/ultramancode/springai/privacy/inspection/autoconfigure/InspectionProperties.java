package io.github.ultramancode.springai.privacy.inspection.autoconfigure;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Common inspection policy and execution limits. */
@ConfigurationProperties("spring.ai.inspection")
public class InspectionProperties {

    /** Enables inspection configuration for explicitly selected ChatClient instances. */
    private boolean enabled;

    /** Maximum number of text segments per model-bound request. */
    private int maxSegments = 64;

    /** Maximum combined text length in UTF-16 code units per model-bound request. */
    private int maxCharacters = 131_072;

    /** Maximum ONNX windows or remote segment requests per inspector and inspection request. */
    private int maxChunks = 256;

    /** Timeout shared by all inspectors for one model-bound request. */
    private Duration timeout = Duration.ofSeconds(10);

    /** Policy applied to operational inspection failures. */
    private InspectionFailurePolicy failurePolicy = InspectionFailurePolicy.BLOCK;

    public InspectionLimits limits() {
        return new InspectionLimits(maxSegments, maxCharacters, maxChunks, timeout);
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

    public int getMaxChunks() {
        return maxChunks;
    }

    public void setMaxChunks(int value) {
        maxChunks = value;
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
