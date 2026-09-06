package io.github.ultramancode.springai.privacy.security.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the optional Spring Security tool boundary. */
@ConfigurationProperties("spring.ai.privacy.security")
public class PrivacySecurityProperties {

    /** Whether authorization-aware tool filtering and execution checks are enabled. */
    private boolean enabled;

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
