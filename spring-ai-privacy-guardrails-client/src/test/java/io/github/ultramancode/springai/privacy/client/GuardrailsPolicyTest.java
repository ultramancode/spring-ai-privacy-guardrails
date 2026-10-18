package io.github.ultramancode.springai.privacy.client;

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
    void disablingOutputPreservesOtherSettingsAndLeavesTheSourcePolicyUnchanged() {
        ToolDisclosurePolicy disclosure = ToolDisclosurePolicy.byToolName(Map.of("lookup", List.of("PERSON")));
        PrivacyResponseInspectionLimits responseLimits =
                new PrivacyResponseInspectionLimits(10, 200, 300, Duration.ofSeconds(4));
        InspectionLimits inspectionLimits = new InspectionLimits(5, 600, Duration.ofSeconds(7));
        GuardrailsPolicy source = GuardrailsPolicy.builder()
                .privacy(privacy -> privacy.toolDisclosurePolicy(disclosure)
                        .outputAction(PrivacyOutputAction.REDACT).outputBlockMessage("Blocked")
                        .responseLimits(responseLimits))
                .inspection(inspection -> inspection.applicationOutput(true).limits(inspectionLimits)
                        .maxOutputFrames(8).outputStreamTimeout(Duration.ofSeconds(9)))
                .toolAuthorization(true).build();

        GuardrailsPolicy modified = source.mutate()
                .privacy(privacy -> privacy.outputProtection(false))
                .inspection(inspection -> inspection.applicationOutput(false))
                .build();

        assertThat(modified.privacy().outputAction()).isNull();
        assertThat(modified.privacy().toolDisclosurePolicy()).isSameAs(disclosure);
        assertThat(modified.privacy().outputBlockMessage()).isEqualTo("Blocked");
        assertThat(modified.privacy().responseLimits()).isEqualTo(responseLimits);
        assertThat(modified.inspection().applicationOutput()).isFalse();
        assertThat(modified.inspection().modelRequests()).isTrue();
        assertThat(modified.inspection().limits()).isEqualTo(inspectionLimits);
        assertThat(modified.inspection().maxOutputFrames()).isEqualTo(8);
        assertThat(modified.inspection().outputStreamTimeout()).isEqualTo(Duration.ofSeconds(9));
        assertThat(modified.toolAuthorization()).isTrue();
        assertThat(source.privacy().outputAction()).isEqualTo(PrivacyOutputAction.REDACT);
        assertThat(source.inspection().applicationOutput()).isTrue();
    }

    @Test
    void reusedBuilderCanSelectSubsetsWithoutChangingPreviouslyBuiltPolicies() {
        GuardrailsPolicy source = GuardrailsPolicy.builder()
                .privacy(privacy -> privacy.outputAction(PrivacyOutputAction.BLOCK))
                .inspection(inspection -> inspection.modelRequests(false).applicationOutput(true))
                .toolAuthorization(true).build();
        GuardrailsPolicy.Builder builder = source.mutate();
        GuardrailsPolicy unchanged = builder.privacy().inspection().build();
        GuardrailsPolicy authorizationOnly = builder.privacy(false).inspection(false).build();
        GuardrailsPolicy inspectionOnly = source.mutate().privacy(false).toolAuthorization(false).build();

        assertThat(unchanged).isEqualTo(source);
        assertThat(authorizationOnly).isEqualTo(GuardrailsPolicy.builder().toolAuthorization(true).build());
        assertThat(inspectionOnly.privacy()).isNull();
        assertThat(inspectionOnly.toolAuthorization()).isFalse();
        assertThat(inspectionOnly.inspection()).isEqualTo(source.inspection());
        assertThatThrownBy(() -> builder.toolAuthorization(false).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
    }
}
