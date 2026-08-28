package io.github.ultramancode.privacy.buildlogic;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.ultramancode.privacy.buildlogic.tasks.VerifyAutomaticModuleNames;
import io.github.ultramancode.privacy.buildlogic.tasks.VerifyDocumentationTranslations;
import io.github.ultramancode.privacy.buildlogic.tasks.VerifyModuleBoundaries;
import io.github.ultramancode.privacy.buildlogic.tasks.VerifyReleaseWorkflowContract;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;

public final class RepositoryVerificationPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new GradleException("privacy.repository-verification must be applied to the root project");
        }
        project.getPluginManager().apply("base");
        project.setGroup(project.getProviders().gradleProperty("group").get());
        project.setVersion(project.getProviders().gradleProperty("version").get());

        List<Project> modules = project.getSubprojects().stream()
                .sorted(Comparator.comparing(Project::getPath))
                .toList();
        for (Project module : modules) {
            module.getPluginManager().apply("privacy.java-conventions");
        }

        PrivacyBuildExtension extension = new PrivacyBuildExtension();
        project.getExtensions().add("privacyBuild", extension);
        TaskProvider<VerifyModuleBoundaries> verifyModuleBoundaries = registerModuleVerification(
                project,
                modules
        );
        TaskProvider<VerifyAutomaticModuleNames> verifyAutomaticModules = registerAutomaticModules(project);
        TaskProvider<VerifyDocumentationTranslations> verifyTranslations = registerTranslations(project);
        TaskProvider<VerifyReleaseWorkflowContract> verifyReleaseWorkflow =
                registerReleaseWorkflowContract(project);

        extension.whenModuleDeclared(spec -> configureModule(
                project,
                spec,
                verifyModuleBoundaries,
                verifyAutomaticModules
        ));
        extension.whenLocalizedDocumentDeclared(document -> configureLocalization(
                project,
                document,
                verifyTranslations
        ));

        TaskProvider<Task> rootCheck = project.getTasks().named("check");
        rootCheck.configure(task -> {
            task.setDescription(
                    "Runs every module check and verifies repository contracts."
            );
            task.dependsOn(modules.stream().map(module -> module.getPath() + ":check").toList());
            task.dependsOn(
                    verifyAutomaticModules,
                    verifyReleaseWorkflow
            );
            task.dependsOn(project.getGradle().includedBuild("build-logic").task(":check"));
        });
        for (Project module : modules) {
            module.getTasks().named("check").configure(task -> task.dependsOn(verifyModuleBoundaries));
        }
    }

    private static TaskProvider<VerifyModuleBoundaries> registerModuleVerification(
            Project project,
            List<Project> modules
    ) {
        TaskProvider<VerifyModuleBoundaries> task = project.getTasks().register(
                "verifyModuleBoundaries",
                VerifyModuleBoundaries.class,
                verification -> {
                    verification.setGroup("verification");
                    verification.setDescription(
                            "Rejects dependency edges that violate the published module architecture."
                    );
                    verification.getConfiguredProjectPaths().set(
                            modules.stream().map(Project::getPath).collect(Collectors.toSet())
                    );
                    verification.getAllowedProjectDependencies().convention(Map.of());
                    verification.getProjectsWithoutExternalMainDependencies().convention(Set.of());
                    verification.getForbiddenExternalGroupPrefixes().convention(Map.of());
                    for (Project module : modules) {
                        verification.dependsOn(module.getPath() + ":writePrivacyModuleMetadata");
                        verification.getModuleMetadataFiles().from(
                                module.getLayout().getBuildDirectory()
                                        .file("privacy-verification/module-metadata.properties")
                        );
                    }
                }
        );
        return task;
    }

    private static TaskProvider<VerifyAutomaticModuleNames> registerAutomaticModules(Project project) {
        return project.getTasks().register(
                "verifyAutomaticModuleNames",
                VerifyAutomaticModuleNames.class,
                verification -> {
                    verification.setGroup("verification");
                    verification.setDescription(
                            "Verifies stable JPMS names and a single package for every published module."
                    );
                    verification.getExpectedAutomaticModuleNames().convention(Map.of());
                    verification.getProjectVersion().set(project.getVersion().toString());
                }
        );
    }

    private static TaskProvider<VerifyDocumentationTranslations> registerTranslations(Project project) {
        return project.getTasks().register(
                "verifyDocTranslations",
                VerifyDocumentationTranslations.class,
                verification -> {
                    verification.setGroup("verification");
                    verification.setDescription(
                            "Rejects missing or stale Korean documentation translations."
                    );
                    verification.getRepositoryDirectory().set(project.getLayout().getProjectDirectory());
                    verification.getLocalizedDocuments().convention(Map.of());
                }
        );
    }

    private static TaskProvider<VerifyReleaseWorkflowContract> registerReleaseWorkflowContract(
            Project project
    ) {
        return project.getTasks().register(
                "verifyReleaseWorkflowContract",
                VerifyReleaseWorkflowContract.class,
                verification -> {
                    verification.setGroup("verification");
                    verification.setDescription(
                            "Verifies durable Central evidence ordering and release consumer preflights."
                    );
                    verification.getWorkflowFile().set(
                            project.getLayout().getProjectDirectory()
                                    .file(".github/workflows/release.yml")
                    );
                }
        );
    }

    private static void configureModule(
            Project rootProject,
            PrivacyModuleSpec spec,
            TaskProvider<VerifyModuleBoundaries> verifyModuleBoundaries,
            TaskProvider<VerifyAutomaticModuleNames> verifyAutomaticModules
    ) {
        Project module = rootProject.findProject(spec.getPath());
        if (module == null) {
            throw new GradleException("Architecture policy references missing project " + spec.getPath());
        }
        String encodedDependencies = String.join(",", spec.getAllowedProjectDependencies().stream()
                .sorted()
                .toList());
        verifyModuleBoundaries.configure(task -> {
            task.getAllowedProjectDependencies().put(spec.getPath(), encodedDependencies);
            if (spec.isAllExternalMainDependenciesDenied()) {
                task.getProjectsWithoutExternalMainDependencies().add(spec.getPath());
            }
            if (!spec.getForbiddenExternalGroupPrefixes().isEmpty()) {
                task.getForbiddenExternalGroupPrefixes().put(
                        spec.getPath(),
                        String.join(",", spec.getForbiddenExternalGroupPrefixes().stream().sorted().toList())
                );
            }
        });

        if (!spec.isPublishable()) {
            return;
        }
        ExtraPropertiesExtension extra = module.getExtensions().getExtraProperties();
        extra.set("privacyAutomaticModuleName", spec.getAutomaticModuleName());
        extra.set("privacyPublicationDisplayName", spec.getPublicationDisplayName());
        module.getPluginManager().apply("privacy.publishing-conventions");
        verifyAutomaticModules.configure(task -> {
            task.dependsOn(spec.getPath() + ":jar");
            task.getExpectedAutomaticModuleNames().put(spec.getName(), spec.getAutomaticModuleName());
            task.getJarFiles().from(
                    module.getTasks().named("jar", Jar.class).flatMap(Jar::getArchiveFile)
            );
        });
    }

    private static void configureLocalization(
            Project project,
            LocalizedDocument document,
            TaskProvider<VerifyDocumentationTranslations> verification
    ) {
        verification.configure(task -> {
            task.getLocalizedDocuments().put(document.sourcePath(), document.targetPath());
            ConfigurableFileCollection files = task.getDocumentFiles();
            files.from(project.file(document.sourcePath()), project.file(document.targetPath()));
        });
    }
}
