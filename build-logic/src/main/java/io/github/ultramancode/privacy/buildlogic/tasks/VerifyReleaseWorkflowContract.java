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
    private static final String CENTRAL_PUBLICATION_STEP =
            "- name: Wait for Maven Central publication";
    private static final String PUBLIC_MODULE_VERIFICATION_STEP =
            "- name: Verify all modules on public Maven Central";
    private static final String PUBLIC_CONSUMER_STEP =
            "- name: Run consumer from public Maven Central";
    private static final String GITHUB_RELEASE_STEP =
            "- name: Publish GitHub release";

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
                "Verified release evidence and public consumer ordering."
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

        int centralPublication = uniqueIndex(
                workflow,
                CENTRAL_PUBLICATION_STEP,
                "Maven Central publication wait step"
        );
        int publicModuleVerification = uniqueIndex(
                workflow,
                PUBLIC_MODULE_VERIFICATION_STEP,
                "public Maven Central module verification step"
        );
        int publicConsumer = uniqueIndex(
                workflow,
                PUBLIC_CONSUMER_STEP,
                "public Maven Central consumer step"
        );
        int githubRelease = uniqueIndex(
                workflow,
                GITHUB_RELEASE_STEP,
                "GitHub Release publication step"
        );
        if (centralPublication >= publicModuleVerification
                || publicModuleVerification >= publicConsumer
                || publicConsumer >= githubRelease) {
            throw new GradleException(
                    "Release workflow must wait for Central publication, verify every public "
                            + "module, run the public consumer, and only then publish GitHub Release"
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
