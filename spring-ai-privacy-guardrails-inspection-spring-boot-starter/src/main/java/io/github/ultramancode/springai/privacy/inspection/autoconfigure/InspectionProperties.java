package io.github.ultramancode.springai.privacy.inspection.autoconfigure;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared inspection service settings under {@code spring.ai.inspection}.
 */
@ConfigurationProperties("spring.ai.inspection")
public class InspectionProperties {

    /** Default policy applied to eligible operational inspection failures. */
    private InspectionFailurePolicy failurePolicy = InspectionFailurePolicy.FAIL_CLOSED;

    /** Failure policy overrides keyed by inspector ID. Unspecified inspectors use the default policy. */
    private Map<String, InspectionFailurePolicy> failurePolicyOverrides = new LinkedHashMap<>();

    /** Inspector IDs that receive session-issued PII tokens without their random part. Empty by default. */
    private Set<String> piiTokenSimplificationInspectors = Set.of();

    public Set<String> getPiiTokenSimplificationInspectors() {
        return piiTokenSimplificationInspectors;
    }

    public void setPiiTokenSimplificationInspectors(Set<String> value) {
        piiTokenSimplificationInspectors = value;
    }

    public InspectionFailurePolicy getFailurePolicy() {
        return failurePolicy;
    }

    public void setFailurePolicy(InspectionFailurePolicy value) {
        failurePolicy = value;
    }

    public Map<String, InspectionFailurePolicy> getFailurePolicyOverrides() {
        return failurePolicyOverrides;
    }

    public void setFailurePolicyOverrides(Map<String, InspectionFailurePolicy> value) {
        failurePolicyOverrides = value;
    }
}
