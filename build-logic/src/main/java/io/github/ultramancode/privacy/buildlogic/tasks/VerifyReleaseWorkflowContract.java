package io.github.ultramancode.privacy.buildlogic.tasks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "This task validates the release workflow and produces no output")
public abstract class VerifyReleaseWorkflowContract extends DefaultTask {

    private static final String EVIDENCE_UPLOAD = "gh release upload \"$RELEASE_TAG\"";
    private static final String CENTRAL_UPLOAD =
            "\"https://central.sonatype.com/api/v1/publisher/upload?";
    private static final String RECOVERY_MARKER =
            "recovery_marker=\"<!-- central-deployment-id:";

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getWorkflowFile();

    @TaskAction
    public void verifyReleaseWorkflow() throws IOException {
        String workflow = Files.readString(
                getWorkflowFile().get().getAsFile().toPath(),
                StandardCharsets.UTF_8
        );
        verify(workflow);
        getLogger().lifecycle(
                "Verified release evidence ordering and Maven-POM-only consumer preflight."
        );
    }

    static void verify(String workflow) {
        int evidenceUpload = uniqueIndex(workflow, EVIDENCE_UPLOAD, "draft evidence upload");
        int centralUpload = uniqueIndex(workflow, CENTRAL_UPLOAD, "Maven Central upload");
        int recoveryMarker = uniqueIndex(workflow, RECOVERY_MARKER, "Central recovery marker");
        if (evidenceUpload >= centralUpload || centralUpload >= recoveryMarker) {
            throw new GradleException(
                    "Release workflow must persist draft evidence before Central upload "
                            + "and save the recovery marker only after Central accepts it"
            );
        }
        if (!workflow.contains("publishedPomOnlyArtifactSmokeTest")) {
            throw new GradleException(
                    "Release workflow must execute publishedPomOnlyArtifactSmokeTest"
            );
        }
    }

    private static int uniqueIndex(String source, String marker, String label) {
        int index = source.indexOf(marker);
        if (index < 0) {
            throw new GradleException("Release workflow is missing " + label);
        }
        if (source.indexOf(marker, index + marker.length()) >= 0) {
            throw new GradleException("Release workflow contains duplicate " + label);
        }
        return index;
    }
}
