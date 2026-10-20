package io.github.ultramancode.springai.privacy.chatclient;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAction;
import io.github.ultramancode.springai.privacy.springai.PrivacyResponseInspectionLimits;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardrailsPolicyTest {
    @Test
    void outputProtectionIsDisabledByDefaultWithTokenizeSelected() {
        GuardrailsPolicy guardrailsPolicy = GuardrailsPolicy.builder().privacy().build();

        assertThat(guardrailsPolicy.privacy().outputProtectionEnabled()).isFalse();
        assertThat(guardrailsPolicy.privacy().outputAction()).isEqualTo(PrivacyOutputAction.TOKENIZE);
    }

    @Test
    void reenablingOutputProtectionRetainsTheSelectedAction() {
        GuardrailsPolicy sourcePolicy = GuardrailsPolicy.builder()
            .privacy(privacyPolicyBuilder -> privacyPolicyBuilder.outputAction(PrivacyOutputAction.REDACT)).build();
        GuardrailsPolicy disabledPolicy = sourcePolicy.mutate()
            .privacy(privacyPolicyBuilder -> privacyPolicyBuilder.outputProtection(false)).build();

        GuardrailsPolicy reenabledPolicy = disabledPolicy.mutate()
            .privacy(privacyPolicyBuilder -> privacyPolicyBuilder.outputProtection(true)).build();

        assertThat(reenabledPolicy.privacy().outputProtectionEnabled()).isTrue();
        assertThat(reenabledPolicy.privacy().outputAction()).isEqualTo(PrivacyOutputAction.REDACT);
    }

    @Test
    void disablingOutputPreservesOtherSettingsAndLeavesTheSourcePolicyUnchanged() {
        ToolDisclosurePolicy disclosure = ToolDisclosurePolicy.byToolName(Map.of("lookup", List.of("PERSON")));
        PrivacyResponseInspectionLimits responseLimits =
            new PrivacyResponseInspectionLimits(10, 200, 300, Duration.ofSeconds(4));
        InspectionLimits inspectionLimits = new InspectionLimits(5, 600, Duration.ofSeconds(7));
        GuardrailsPolicy sourcePolicy = GuardrailsPolicy.builder()
            .privacy(privacyPolicyBuilder -> privacyPolicyBuilder.toolDisclosurePolicy(disclosure)
                .outputAction(PrivacyOutputAction.REDACT).outputBlockMessage("Blocked")
                .responseLimits(responseLimits))
            .inspection(inspectionPolicyBuilder -> inspectionPolicyBuilder
                .applicationOutputInspectionEnabled(true).limits(inspectionLimits)
                .maxOutputFrames(8).outputStreamTimeout(Duration.ofSeconds(9)))
            .toolAuthorization(true).build();

        GuardrailsPolicy modified = sourcePolicy.mutate()
            .privacy(privacyPolicyBuilder -> privacyPolicyBuilder.outputProtection(false))
            .inspection(inspectionPolicyBuilder -> inspectionPolicyBuilder
                .applicationOutputInspectionEnabled(false))
            .build();

        assertThat(modified.privacy().outputProtectionEnabled()).isFalse();
        assertThat(modified.privacy().outputAction()).isEqualTo(PrivacyOutputAction.REDACT);
        assertThat(modified.privacy().toolDisclosurePolicy()).isSameAs(disclosure);
        assertThat(modified.privacy().outputBlockMessage()).isEqualTo("Blocked");
        assertThat(modified.privacy().responseLimits()).isEqualTo(responseLimits);
        assertThat(modified.inspection().applicationOutputInspectionEnabled()).isFalse();
        assertThat(modified.inspection().modelRequestInspectionEnabled()).isTrue();
        assertThat(modified.inspection().limits()).isEqualTo(inspectionLimits);
        assertThat(modified.inspection().maxOutputFrames()).isEqualTo(8);
        assertThat(modified.inspection().outputStreamTimeout()).isEqualTo(Duration.ofSeconds(9));
        assertThat(modified.toolAuthorization()).isTrue();
        assertThat(sourcePolicy.privacy().outputAction()).isEqualTo(PrivacyOutputAction.REDACT);
        assertThat(sourcePolicy.privacy().outputProtectionEnabled()).isTrue();
        assertThat(sourcePolicy.inspection().applicationOutputInspectionEnabled()).isTrue();
    }

    @Test
    void reusedBuilderCanSelectSubsetsWithoutChangingPreviouslyBuiltPolicies() {
        GuardrailsPolicy sourcePolicy = GuardrailsPolicy.builder()
            .privacy(privacyPolicyBuilder -> privacyPolicyBuilder.outputAction(PrivacyOutputAction.BLOCK))
            .inspection(inspectionPolicyBuilder -> inspectionPolicyBuilder
                .modelRequestInspectionEnabled(false).applicationOutputInspectionEnabled(true))
            .toolAuthorization(true).build();
        GuardrailsPolicy.Builder guardrailsPolicyBuilder = sourcePolicy.mutate();
        GuardrailsPolicy unchanged = guardrailsPolicyBuilder.privacy().inspection().build();
        GuardrailsPolicy authorizationOnly = guardrailsPolicyBuilder.privacy(false).inspection(false).build();
        GuardrailsPolicy inspectionOnly = sourcePolicy.mutate().privacy(false).toolAuthorization(false).build();

        assertThat(unchanged).isEqualTo(sourcePolicy);
        assertThat(authorizationOnly).isEqualTo(GuardrailsPolicy.builder().toolAuthorization(true).build());
        assertThat(inspectionOnly.privacy()).isNull();
        assertThat(inspectionOnly.toolAuthorization()).isFalse();
        assertThat(inspectionOnly.inspection()).isEqualTo(sourcePolicy.inspection());
        assertThatThrownBy(() -> guardrailsPolicyBuilder.toolAuthorization(false).build())
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
    }
}
