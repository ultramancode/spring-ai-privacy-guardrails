package io.github.ultramancode.privacy.buildlogic.tasks;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

class VerifyReleaseWorkflowContractTest {

    @Test
    void acceptsDurableEvidenceAndPublicReleaseOrdering() {
        String workflow = validWorkflow();

        assertThatCode(() -> VerifyReleaseWorkflowContract.verify(workflow))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMarkerThatCanExistBeforeDraftEvidence() {
        String workflow = validWorkflow().replace(
                "gh release upload \"$RELEASE_TAG\"\n"
                        + "\"https://central.sonatype.com/api/v1/publisher/upload?\n"
                        + "recovery_marker=\"<!-- central-deployment-id:",
                "\"https://central.sonatype.com/api/v1/publisher/upload?\n"
                        + "recovery_marker=\"<!-- central-deployment-id:\n"
                        + "gh release upload \"$RELEASE_TAG\""
        );

        assertThatThrownBy(() -> VerifyReleaseWorkflowContract.verify(workflow))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("persist draft evidence before Central upload");
    }

    @Test
    void rejectsWorkflowThatOmitsPomOnlyConsumerPreflight() {
        String workflow = validWorkflow().replace("publishedPomOnlyArtifactSmokeTest\n", "");

        assertThatThrownBy(() -> VerifyReleaseWorkflowContract.verify(workflow))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("publishedPomOnlyArtifactSmokeTest");
    }

    @Test
    void rejectsGitHubReleaseBeforePublicArtifactVerification() {
        String withoutRelease = validWorkflow().replace("- name: Publish GitHub release\n", "");
        String workflow = withoutRelease.replace(
                "- name: Verify all modules on public Maven Central",
                "- name: Publish GitHub release\n"
                        + "- name: Verify all modules on public Maven Central"
        );

        assertThatThrownBy(() -> VerifyReleaseWorkflowContract.verify(workflow))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("only then publish GitHub Release");
    }

    private static String validWorkflow() {
        return """
                publishedPomOnlyArtifactSmokeTest
                gh release upload "$RELEASE_TAG"
                "https://central.sonatype.com/api/v1/publisher/upload?
                recovery_marker="<!-- central-deployment-id:
                - name: Wait for Maven Central publication
                - name: Verify all modules on public Maven Central
                - name: Run consumer from public Maven Central
                - name: Publish GitHub release
                """;
    }
}
