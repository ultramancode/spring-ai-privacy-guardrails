package io.github.ultramancode.springai.privacy.client.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Feature selection for the default client policy under spring.ai.guardrails. */
@ConfigurationProperties("spring.ai.guardrails")
public class GuardrailsProperties {
    /** Enables model and tool privacy in the default policy. */
    private boolean privacy;
    /** Enables inspection before each model request in the default policy. */
    private boolean inspection;
    /** Enables tool definition and execution authorization in the default policy. */
    private boolean toolAuthorization;

    public boolean isPrivacy() {
        return privacy;
    }

    public void setPrivacy(boolean privacy) {
        this.privacy = privacy;
    }

    public boolean isInspection() {
        return inspection;
    }

    public void setInspection(boolean inspection) {
        this.inspection = inspection;
    }

    public boolean isToolAuthorization() {
        return toolAuthorization;
    }

    public void setToolAuthorization(boolean toolAuthorization) {
        this.toolAuthorization = toolAuthorization;
    }
}
