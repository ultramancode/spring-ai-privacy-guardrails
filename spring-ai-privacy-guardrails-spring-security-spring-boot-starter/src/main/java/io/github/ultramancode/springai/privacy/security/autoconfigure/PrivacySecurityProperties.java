package io.github.ultramancode.springai.privacy.security.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;

/** Configuration for the optional Spring Security tool boundary. */
@ConfigurationProperties("spring.ai.privacy.security")
public class PrivacySecurityProperties {

    /** Legacy opt-out for tool-authorization auto-configuration. */
    private boolean enabled = true;

    /** @deprecated Omit this property and select tool authorization with the client factory. */
    @Deprecated(since = "0.3.0", forRemoval = true)
    @DeprecatedConfigurationProperty(reason = "Tool authorization infrastructure is prepared from a tool "
            + "AuthorizationManager or SpringSecurityToolBoundary. Select the client factory; false remains "
            + "a compatibility opt-out.")
    public boolean isEnabled() {
        return this.enabled;
    }

    /** @deprecated Retained for the legacy auto-configuration opt-out. */
    @Deprecated(since = "0.3.0", forRemoval = true)
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
