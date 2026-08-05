package io.github.ultramancode.privacy.buildlogic.snapshot;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileTree;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.TaskProvider;

public final class PublicSnapshotPlugin implements Plugin<Project> {

    private static final String BUILD_LOGIC_PACKAGE_PATH =
            "build-logic/src/main/java/io/github/ultramancode/privacy/buildlogic/";

    private static final String TASK_PACKAGE_PATH = BUILD_LOGIC_PACKAGE_PATH + "tasks/";

    private static final String SNAPSHOT_PACKAGE_PATH =
            BUILD_LOGIC_PACKAGE_PATH + "snapshot/";

    private static final List<String> EXECUTABLE_PUBLIC_PATHS = List.of(
            "gradlew",
            "scripts/check-doc-i18n.sh",
            "scripts/verify-release-sbom.sh"
    );

    private static final List<String> REQUIRED_PUBLIC_PATHS = List.of(
            ".github/workflows/ci.yml",
            ".github/workflows/release.yml",
            ".gitattributes",
            ".gitignore",
            "README.md",
            "LICENSE",
            "CHANGELOG.md",
            "CONTRIBUTING.md",
            "CODE_OF_CONDUCT.md",
            "SECURITY.md",
            "build.gradle",
            "settings.gradle",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            "gradle/wrapper/gradle-wrapper.jar",
            "gradle/wrapper/gradle-wrapper.properties",
            "scripts/check-doc-i18n.sh",
            "scripts/verify-release-sbom.sh",
            "build-logic/build.gradle",
            "build-logic/settings.gradle",
            BUILD_LOGIC_PACKAGE_PATH + "JavaConventionsPlugin.java",
            BUILD_LOGIC_PACKAGE_PATH + "LocalizedDocument.java",
            BUILD_LOGIC_PACKAGE_PATH + "PrivacyBuildExtension.java",
            BUILD_LOGIC_PACKAGE_PATH + "PrivacyModuleSpec.java",
            BUILD_LOGIC_PACKAGE_PATH + "PublishingConventionsPlugin.java",
            BUILD_LOGIC_PACKAGE_PATH + "ReleasePlugin.java",
            BUILD_LOGIC_PACKAGE_PATH + "RepositoryVerificationPlugin.java",
            TASK_PACKAGE_PATH + "ValidateReleaseVersion.java",
            TASK_PACKAGE_PATH + "VerifyAutomaticModuleNames.java",
            TASK_PACKAGE_PATH + "VerifyCentralStagingRepository.java",
            TASK_PACKAGE_PATH + "VerifyDocumentationTranslations.java",
            TASK_PACKAGE_PATH + "VerifyModuleBoundaries.java",
            TASK_PACKAGE_PATH + "VerifyPublishedModuleBoundaries.java",
            TASK_PACKAGE_PATH + "VerifyReleaseWorkflowContract.java",
            TASK_PACKAGE_PATH + "WriteModuleMetadata.java",
            SNAPSHOT_PACKAGE_PATH + "PreparePublicSnapshot.java",
            SNAPSHOT_PACKAGE_PATH + "VerifyPublicSnapshotArchive.java",
            SNAPSHOT_PACKAGE_PATH + "PublicSnapshotPlugin.java",
            "build-logic/src/test/java/io/github/ultramancode/privacy/buildlogic/PrivacyModuleSpecTest.java",
            "build-logic/src/test/java/io/github/ultramancode/privacy/buildlogic/snapshot/PreparePublicSnapshotTest.java",
            "build-logic/src/test/java/io/github/ultramancode/privacy/buildlogic/tasks/VerifyPublishedModuleBoundariesTest.java",
            "build-logic/src/test/java/io/github/ultramancode/privacy/buildlogic/tasks/VerifyReleaseWorkflowContractTest.java",
            "spring-ai-privacy-guardrails-core/build.gradle",
            "spring-ai-privacy-guardrails-opennlp/build.gradle",
            "spring-ai-privacy-guardrails-opennlp-spring-boot-starter/build.gradle",
            "spring-ai-privacy-guardrails-presidio/build.gradle",
            "spring-ai-privacy-guardrails-presidio-spring-boot-starter/build.gradle",
            "spring-ai-privacy-guardrails-spring-ai/build.gradle",
            "spring-ai-privacy-guardrails-spring-boot-starter/build.gradle",
            "spring-ai-privacy-guardrails-test/build.gradle",
            "spring-ai-privacy-guardrails-benchmarks/build.gradle",
            "samples/spring-ai-demo/.env.example",
            "samples/spring-ai-demo/README.md",
            "samples/spring-ai-demo/build.gradle",
            "samples/spring-ai-demo/src/openAiCompatibleLiveTest/java/io/github/ultramancode/springai/privacy/sample/LiveModelTestApplication.java",
            "samples/spring-ai-demo/src/openAiCompatibleLiveTest/java/io/github/ultramancode/springai/privacy/sample/OpenAiCompatibleLiveIntegrationTest.java",
            "samples/published-artifact-consumer/build.gradle",
            "samples/published-artifact-consumer/settings.gradle",
            "samples/published-artifact-consumer/src/main/java/example/PublishedArtifactConsumer.java"
    );

    @Override
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new GradleException("privacy.public-snapshot must be applied to the root project");
        }

        Provider<Directory> snapshotOutput = project.getLayout().getBuildDirectory().dir("public-snapshot");
        Provider<Directory> snapshotWorkspace = snapshotOutput.map(directory -> directory.dir("work"));
        Provider<Directory> snapshotTree = snapshotWorkspace.map(directory -> directory.dir("tree"));
        Provider<Directory> dotfileStaging = snapshotWorkspace.map(
                directory -> directory.dir("root-dotfiles")
        );
        Provider<RegularFile> sourceArchive = snapshotWorkspace.map(
                directory -> directory.file("candidate-source.zip")
        );
        Provider<RegularFile> temporaryIndex = snapshotWorkspace.map(
                directory -> directory.file("candidate.index")
        );
        ConfigurableFileTree policyFiles = project.fileTree(project.getLayout().getProjectDirectory(), tree -> {
            tree.include("**/public-snapshot-policy.properties");
            tree.exclude("**/build/**", "**/.gradle/**");
        });

        TaskProvider<PreparePublicSnapshot> prepare = project.getTasks().register(
                "preparePublicSnapshot",
                PreparePublicSnapshot.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Builds and inspects a sanitized public-candidate tree.");
                    task.getRootDirectory().set(project.getLayout().getProjectDirectory());
                    task.getWorkspaceDirectory().set(snapshotWorkspace);
                    task.getSnapshotTreeDirectory().set(snapshotTree);
                    task.getDotfileStagingDirectory().set(dotfileStaging);
                    task.getSourceArchive().set(sourceArchive);
                    task.getTemporaryIndexFile().set(temporaryIndex);
                    task.getSnapshotRef().set(project.getProviders().gradleProperty("publicSnapshotRef"));
                    task.getRequiredPublicPaths().set(REQUIRED_PUBLIC_PATHS);
                    task.getExecutablePublicPaths().set(EXECUTABLE_PUBLIC_PATHS);
                    task.getPolicyFiles().from(policyFiles);
                    task.getOutputs().upToDateWhen(ignored -> false);
                }
        );

        TaskProvider<Exec> publicBuild = project.getTasks().register(
                "verifyPublicSnapshotBuild",
                Exec.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Runs clean check from the sanitized public-candidate tree.");
                    task.dependsOn(prepare);
                    task.setWorkingDir(snapshotTree.get().getAsFile());
                    task.commandLine(publicBuildCommand(project));
                }
        );

        project.getTasks().register(
                "verifyPublicSnapshot",
                VerifyPublicSnapshotArchive.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Verifies and creates the reproducible public-candidate archive.");
                    task.dependsOn(prepare, publicBuild);
                    task.from(snapshotTree, spec -> {
                        spec.exclude("**/build/**", "**/.gradle/**");
                        spec.exclude(EXECUTABLE_PUBLIC_PATHS);
                    });
                    for (String executablePath : EXECUTABLE_PUBLIC_PATHS) {
                        task.from(snapshotTree.map(directory -> directory.file(executablePath)), spec -> {
                            int separator = executablePath.lastIndexOf('/');
                            if (separator >= 0) {
                                spec.into(executablePath.substring(0, separator));
                            }
                            spec.filePermissions(permissions -> permissions.unix("rwxr-xr-x"));
                        });
                    }
                    task.from(dotfileStaging, spec -> spec.rename(name -> "." + name));
                    task.getDestinationDirectory().set(snapshotOutput);
                    task.getArchiveFileName().set("spring-ai-privacy-guardrails-public.zip");
                    task.setPreserveFileTimestamps(false);
                    task.setReproducibleFileOrder(true);
                    task.setMetadataCharset("UTF-8");
                    task.getRequiredPublicPaths().set(REQUIRED_PUBLIC_PATHS);
                    task.getExecutablePublicPaths().set(EXECUTABLE_PUBLIC_PATHS);
                }
        );
    }

    private static List<String> publicBuildCommand(Project project) {
        File gradleHome = project.getGradle().getGradleHomeDir();
        if (gradleHome == null) {
            throw new GradleException("Cannot locate the Gradle installation for public snapshot verification");
        }
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT)
                .contains("win");
        File executable = new File(
                new File(gradleHome, "bin"),
                windows ? "gradle.bat" : "gradle"
        );
        if (!executable.isFile()) {
            throw new GradleException(
                    "Gradle executable is missing for public snapshot verification: " + executable
            );
        }

        List<String> command = new ArrayList<>();
        if (windows) {
            command.add(System.getenv().getOrDefault("ComSpec", "cmd.exe"));
            command.add("/d");
            command.add("/c");
        }
        command.add(executable.getAbsolutePath());
        command.add("clean");
        command.add("check");
        command.add("--no-daemon");
        command.add("-Pversion=" + project.getVersion());
        Object testJavaVersion = project.findProperty("testJavaVersion");
        if (testJavaVersion != null) {
            command.add("-PtestJavaVersion=" + testJavaVersion);
        }
        return List.copyOf(command);
    }
}
