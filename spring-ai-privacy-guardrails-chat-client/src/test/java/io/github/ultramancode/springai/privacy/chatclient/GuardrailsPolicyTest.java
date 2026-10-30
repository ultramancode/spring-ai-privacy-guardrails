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
    void outputTokenSimplificationIsOptInAndSurvivesMutation() {
        GuardrailsPolicy.Privacy original = GuardrailsPolicy.Privacy.builder()
            .outputProtection().simplifyTokens(true).redact().build();

        assertThat(original.outputProtection().simplifyTokens()).isTrue();
        assertThat(original.mutate().build().outputProtection()).isEqualTo(original.outputProtection());
        assertThat(original.mutate().outputProtection().tokenize().build().outputProtection().simplifyTokens()).isFalse();
        assertThat(new GuardrailsPolicy.OutputProtection(PrivacyOutputAction.TOKENIZE, "blocked").simplifyTokens()).isFalse();
    }

    @Test
    void reusingSettingsBuildersPreservesPreviouslyBuiltPolicies() {
        GuardrailsPolicy.PrivacyBuilder privacyPolicyBuilder = GuardrailsPolicy.Privacy.builder();
        GuardrailsPolicy.InspectionBuilder inspectionPolicyBuilder = GuardrailsPolicy.Inspection.builder();
        GuardrailsPolicy sourcePolicy = GuardrailsPolicy.builder()
            .privacy(privacyPolicyBuilder.build())
            .inspection(inspectionPolicyBuilder.build())
            .build();

        privacyPolicyBuilder
            .outputProtection()
            .block();
        inspectionPolicyBuilder
            .modelRequestInspectionEnabled(false)
            .finalOutputInspectionEnabled(true);
        GuardrailsPolicy modifiedPolicy = sourcePolicy.mutate()
            .privacy(privacyPolicyBuilder.build())
            .inspection(inspectionPolicyBuilder.build())
            .build();

        assertThat(sourcePolicy.privacy().outputProtection()).isNull();
        assertThat(sourcePolicy.inspection().modelRequestInspectionEnabled()).isTrue();
        assertThat(sourcePolicy.inspection().finalOutputInspectionEnabled()).isFalse();
        assertThat(modifiedPolicy.privacy().outputProtection().action()).isEqualTo(PrivacyOutputAction.BLOCK);
        assertThat(modifiedPolicy.privacy().outputProtection().blockMessage())
            .isEqualTo("Response blocked by privacy guardrail.");
        assertThat(modifiedPolicy.inspection().modelRequestInspectionEnabled()).isFalse();
        assertThat(modifiedPolicy.inspection().finalOutputInspectionEnabled()).isTrue();
    }

    @Test
    void mutatingPrivacyAndInspectionPreservesUnchangedSettings() {
        ToolDisclosurePolicy toolDisclosurePolicy = ToolDisclosurePolicy.allowEntityTypesByToolName(
            Map.of("lookup", List.of("PERSON")));
        PrivacyResponseInspectionLimits responseLimits =
            new PrivacyResponseInspectionLimits(10, 200, 300, Duration.ofSeconds(4));
        InspectionLimits inspectionLimits = new InspectionLimits(5, 600, Duration.ofSeconds(7));
        GuardrailsPolicy.OutputProtection outputProtection =
            new GuardrailsPolicy.OutputProtection(PrivacyOutputAction.BLOCK, "Blocked");
        GuardrailsPolicy.Privacy privacyPolicy = GuardrailsPolicy.Privacy.builder()
            .toolDisclosurePolicy(toolDisclosurePolicy)
            .outputProtection(outputProtection)
            .responseLimits(responseLimits)
            .build();
        GuardrailsPolicy.Inspection inspectionPolicy = GuardrailsPolicy.Inspection.builder()
            .finalOutputInspectionEnabled(true)
            .limits(inspectionLimits)
            .maxOutputFrames(8)
            .outputStreamTimeout(Duration.ofSeconds(9))
            .build();
        GuardrailsPolicy sourcePolicy = GuardrailsPolicy.builder()
            .privacy(privacyPolicy)
            .inspection(inspectionPolicy)
            .toolAuthorization(true)
            .build();

        GuardrailsPolicy modifiedPolicy = sourcePolicy.mutate()
            .privacy(privacyPolicy.mutate()
                .outputProtection()
                .disable()
                .build())
            .inspection(inspectionPolicy.mutate()
                .finalOutputInspectionEnabled(false)
                .build())
            .build();

        assertThat(privacyPolicy.mutate()
            .build())
            .isEqualTo(privacyPolicy);
        assertThat(modifiedPolicy.privacy().outputProtection()).isNull();
        assertThat(modifiedPolicy.privacy().toolDisclosurePolicy()).isSameAs(toolDisclosurePolicy);
        assertThat(modifiedPolicy.privacy().responseLimits()).isEqualTo(responseLimits);
        assertThat(modifiedPolicy.inspection().finalOutputInspectionEnabled()).isFalse();
        assertThat(modifiedPolicy.inspection().modelRequestInspectionEnabled()).isTrue();
        assertThat(modifiedPolicy.inspection().limits()).isEqualTo(inspectionLimits);
        assertThat(modifiedPolicy.inspection().maxOutputFrames()).isEqualTo(8);
        assertThat(modifiedPolicy.inspection().outputStreamTimeout()).isEqualTo(Duration.ofSeconds(9));
        assertThat(modifiedPolicy.toolAuthorization()).isTrue();
        assertThat(sourcePolicy.privacy().outputProtection()).isEqualTo(outputProtection);
        assertThat(sourcePolicy.inspection().finalOutputInspectionEnabled()).isTrue();
    }

    @Test
    void reusedBuilderCanSelectSubsetsWithoutChangingPreviouslyBuiltPolicies() {
        GuardrailsPolicy sourcePolicy = GuardrailsPolicy.builder()
            .privacy(privacyPolicyBuilder -> privacyPolicyBuilder
                .outputProtection()
                .block())
            .inspection(inspectionPolicyBuilder -> inspectionPolicyBuilder
                .modelRequestInspectionEnabled(false)
                .finalOutputInspectionEnabled(true))
            .toolAuthorization(true)
            .build();
        GuardrailsPolicy.Builder guardrailsPolicyBuilder = sourcePolicy.mutate();
        GuardrailsPolicy unchangedPolicy = guardrailsPolicyBuilder
            .privacy()
            .inspection()
            .build();
        GuardrailsPolicy reselectedPolicy = guardrailsPolicyBuilder
            .privacy(true)
            .inspection(true)
            .build();
        GuardrailsPolicy authorizationOnlyPolicy = guardrailsPolicyBuilder
            .privacy(false)
            .inspection(false)
            .build();
        GuardrailsPolicy inspectionOnlyPolicy = sourcePolicy.mutate()
            .privacy(false)
            .toolAuthorization(false)
            .build();

        assertThat(unchangedPolicy).isEqualTo(sourcePolicy);
        assertThat(reselectedPolicy).isEqualTo(sourcePolicy);
        assertThat(authorizationOnlyPolicy.privacy()).isNull();
        assertThat(authorizationOnlyPolicy.inspection()).isNull();
        assertThat(authorizationOnlyPolicy.toolAuthorization()).isTrue();
        assertThat(inspectionOnlyPolicy.privacy()).isNull();
        assertThat(inspectionOnlyPolicy.toolAuthorization()).isFalse();
        assertThat(inspectionOnlyPolicy.inspection()).isEqualTo(sourcePolicy.inspection());
        assertThatThrownBy(() -> guardrailsPolicyBuilder
            .toolAuthorization(false)
            .build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one");
    }
}
