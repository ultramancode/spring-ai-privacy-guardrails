package io.github.ultramancode.privacy.buildlogic.tasks;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;

class VerifyReleaseWorkflowContractTest {

    @Test
    void acceptsDurableEvidenceBeforeCentralAndMarkerPersistence() {
        String workflow = """
                publishedPomOnlyArtifactSmokeTest
                gh release upload "$RELEASE_TAG"
                "https://central.sonatype.com/api/v1/publisher/upload?
                recovery_marker="<!-- central-deployment-id:
                """;

        assertThatCode(() -> VerifyReleaseWorkflowContract.verify(workflow))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMarkerThatCanExistBeforeDraftEvidence() {
        String workflow = """
                publishedPomOnlyArtifactSmokeTest
                "https://central.sonatype.com/api/v1/publisher/upload?
                recovery_marker="<!-- central-deployment-id:
                gh release upload "$RELEASE_TAG"
                """;

        assertThatThrownBy(() -> VerifyReleaseWorkflowContract.verify(workflow))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("persist draft evidence before Central upload");
    }

    @Test
    void rejectsWorkflowThatOmitsPomOnlyConsumerPreflight() {
        String workflow = """
                gh release upload "$RELEASE_TAG"
                "https://central.sonatype.com/api/v1/publisher/upload?
                recovery_marker="<!-- central-deployment-id:
                """;

        assertThatThrownBy(() -> VerifyReleaseWorkflowContract.verify(workflow))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("publishedPomOnlyArtifactSmokeTest");
    }
}
