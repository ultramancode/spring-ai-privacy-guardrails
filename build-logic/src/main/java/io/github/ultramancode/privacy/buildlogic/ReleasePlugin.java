package io.github.ultramancode.privacy.buildlogic;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import io.github.ultramancode.privacy.buildlogic.tasks.ValidateReleaseVersion;
import io.github.ultramancode.privacy.buildlogic.tasks.VerifyCentralStagingRepository;
import io.github.ultramancode.privacy.buildlogic.tasks.VerifyPublishedModuleBoundaries;
import org.cyclonedx.Version;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.gradle.CyclonedxAggregateTask;
import org.cyclonedx.gradle.CyclonedxDirectTask;
import org.cyclonedx.gradle.BaseCyclonedxTask;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.ExternalReference;
import org.cyclonedx.model.License;
import org.cyclonedx.model.LicenseChoice;
import org.cyclonedx.parsers.JsonParser;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Delete;
import org.gradle.api.tasks.GradleBuild;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Zip;

public final class ReleasePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        if (project != project.getRootProject()) {
            throw new GradleException("privacy.release must be applied to the root project");
        }
        PrivacyBuildExtension extension = project.getExtensions().findByType(PrivacyBuildExtension.class);
        if (extension == null) {
            throw new GradleException(
                    "privacy.repository-verification must be applied before privacy.release"
            );
        }

        TaskProvider<Delete> cleanVerification = project.getTasks().register(
                "cleanVerificationRepository",
                Delete.class,
                task -> task.delete(project.getLayout().getBuildDirectory().dir("verification-repository"))
        );
        TaskProvider<VerifyPublishedModuleBoundaries> verifyPublished = project.getTasks().register(
                "verifyPublishedModuleBoundaries",
                VerifyPublishedModuleBoundaries.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription(
                            "Verifies generated POM dependency graphs and Spring-independent boundaries."
                    );
                    task.dependsOn(project.getTasks().named("verifyAutomaticModuleNames"));
                    task.getSpringIndependentPublications().convention(Set.of());
                    task.getExpectedFirstPartyDependencies().convention(Map.of());
                    task.getPublicationGroup().set(project.getGroup().toString());
                    task.getPublicationVersion().set(project.getVersion().toString());
                }
        );
        TaskProvider<GradleBuild> artifactSmokeTest = project.getTasks().register(
                "publishedArtifactSmokeTest",
                GradleBuild.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription(
                            "Publishes every library module and runs an external Boot consumer from an isolated repository."
                    );
                    task.dependsOn(verifyPublished);
                    task.setDir(project.file("samples/published-artifact-consumer"));
                    task.setTasks(List.of("clean", "run"));
                    task.getStartParameter().setProjectProperties(Map.of(
                            "privacyRepository",
                            project.getLayout().getBuildDirectory()
                                    .dir("verification-repository")
                                    .get()
                                    .getAsFile()
                                    .getAbsolutePath(),
                            "privacyVersion",
                            project.getVersion().toString()
                    ));
                }
        );
        Provider<Directory> pomOnlyConsumerDirectory = project.getLayout()
                .getBuildDirectory()
                .dir("published-pom-only-consumer");
        TaskProvider<Sync> preparePomOnlyConsumer = project.getTasks().register(
                "preparePublishedPomOnlyArtifactConsumer",
                Sync.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription("Prepares an isolated Maven-POM-only external consumer.");
                    task.mustRunAfter(project.getTasks().named("clean"));
                    task.from(
                            project.getLayout().getProjectDirectory()
                                    .dir("samples/published-artifact-consumer"),
                            spec -> spec.exclude("build/**", ".gradle/**")
                    );
                    task.into(pomOnlyConsumerDirectory);
                }
        );
        TaskProvider<GradleBuild> pomOnlyArtifactSmokeTest = project.getTasks().register(
                "publishedPomOnlyArtifactSmokeTest",
                GradleBuild.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription(
                            "Runs the external Boot consumer using Maven POM metadata only."
                    );
                    task.dependsOn(verifyPublished, preparePomOnlyConsumer);
                    task.mustRunAfter(artifactSmokeTest);
                    task.setDir(pomOnlyConsumerDirectory.get().getAsFile());
                    task.setTasks(List.of("clean", "run"));
                    task.getStartParameter().setProjectProperties(Map.of(
                            "privacyRepository",
                            project.getLayout().getBuildDirectory()
                                    .dir("verification-repository")
                                    .get()
                                    .getAsFile()
                                    .getAbsolutePath(),
                            "privacyVersion",
                            project.getVersion().toString(),
                            "privacyPomOnly",
                            "true",
                            "privacyConsumerBuildName",
                            "spring-ai-privacy-guardrails-pom-only-consumer"
                    ));
                }
        );

        configureCycloneDx(project);

        TaskProvider<ValidateReleaseVersion> validateReleaseVersion = project.getTasks().register(
                "validateReleaseVersion",
                ValidateReleaseVersion.class,
                task -> {
                    task.setGroup("publishing");
                    task.setDescription(
                            "Rejects snapshot and malformed versions before building a Central Portal bundle."
                    );
                    task.getReleaseVersion().set(project.getProviders().gradleProperty("version"));
                }
        );
        TaskProvider<Delete> cleanCentral = project.getTasks().register(
                "cleanCentralStagingRepository",
                Delete.class,
                task -> task.delete(project.getLayout().getBuildDirectory().dir("central-staging-repository"))
        );
        TaskProvider<Delete> pruneCentralChecksums = project.getTasks().register(
                "pruneCentralStagingRepositoryChecksums",
                Delete.class,
                task -> {
                    task.setGroup("publishing");
                    task.setDescription(
                            "Removes checksums that are intentionally omitted from the Central Portal bundle."
                    );
                    task.delete(project.fileTree(
                            project.getLayout().getBuildDirectory().dir("central-staging-repository"),
                            files -> files.include(
                                    "**/*.sha256",
                                    "**/*.sha512",
                                    "**/*.asc.md5",
                                    "**/*.asc.sha1"
                            )
                    ));
                }
        );
        TaskProvider<VerifyCentralStagingRepository> verifyCentral = project.getTasks().register(
                "verifyCentralStagingRepository",
                VerifyCentralStagingRepository.class,
                task -> {
                    task.setGroup("publishing");
                    task.setDescription(
                            "Verifies the complete signed Maven layout before creating the Central Portal bundle."
                    );
                    task.dependsOn(verifyPublished);
                    task.getRepositoryDirectory().set(
                            project.getLayout().getBuildDirectory().dir("central-staging-repository")
                    );
                    task.dependsOn(pruneCentralChecksums);
                    task.getGroupPath().set(project.getGroup().toString().replace('.', '/'));
                    task.getReleaseVersion().set(project.getProviders().gradleProperty("version"));
                    task.getPublishableProjects().convention(Set.of());
                    task.getLicenseFile().set(
                            project.getLayout().getProjectDirectory().file("LICENSE")
                    );
                }
        );
        project.getTasks().register("centralPortalBundle", Zip.class, task -> {
            task.setGroup("publishing");
            task.setDescription(
                    "Builds the signed Maven-layout bundle accepted by the Maven Central Portal."
            );
            task.dependsOn(verifyCentral);
            task.from(
                    project.getLayout().getBuildDirectory().dir("central-staging-repository"),
                    spec -> {
                        spec.include("io/**");
                        spec.exclude("**/maven-metadata.*");
                    }
            );
            task.getDestinationDirectory().set(
                    project.getLayout().getBuildDirectory().dir("distributions")
            );
            task.getArchiveFileName().set(
                    project.getName() + "-" + project.getVersion() + "-central-bundle.zip"
            );
            task.setPreserveFileTimestamps(false);
            task.setReproducibleFileOrder(true);
        });

        AtomicReference<TaskProvider<? extends Task>> previousVerification = new AtomicReference<>();
        AtomicReference<TaskProvider<? extends Task>> previousCentral = new AtomicReference<>();
        extension.whenModuleDeclared(spec -> {
            if (!spec.isPublishable()) {
                return;
            }
            Project module = project.project(spec.getPath());
            TaskProvider<? extends Task> verificationPublication = module.getTasks().named(
                    "publishMavenJavaPublicationToVerificationRepository"
            );
            TaskProvider<? extends Task> priorVerification = previousVerification.getAndSet(
                    verificationPublication
            );
            verificationPublication.configure(task -> {
                task.dependsOn(cleanVerification);
                if (priorVerification != null) {
                    task.mustRunAfter(priorVerification);
                }
            });
            artifactSmokeTest.configure(task -> task.dependsOn(verificationPublication));
            pomOnlyArtifactSmokeTest.configure(task -> task.dependsOn(verificationPublication));

            TaskProvider<? extends Task> centralPublication = module.getTasks().named(
                    "publishMavenJavaPublicationToCentralStagingRepository"
            );
            TaskProvider<? extends Task> priorCentral = previousCentral.getAndSet(centralPublication);
            centralPublication.configure(task -> {
                task.dependsOn(validateReleaseVersion, cleanCentral);
                if (priorCentral != null) {
                    task.mustRunAfter(priorCentral);
                }
            });
            pruneCentralChecksums.configure(task -> task.dependsOn(centralPublication));
            verifyCentral.configure(task -> {
                task.getPublishableProjects().add(spec.getName());
            });

            module.getTasks().named("cyclonedxDirectBom", CyclonedxDirectTask.class).configure(task -> {
                task.setEnabled(true);
                task.getIncludeConfigs().set(List.of("runtimeClasspath"));
                configureSbomIdentity(task);
            });

            verifyPublished.configure(task -> {
                task.dependsOn(spec.getPath() + ":generatePomFileForMavenJavaPublication");
                task.getPomFiles().from(
                        module.getLayout().getBuildDirectory()
                                .file("publications/mavenJava/pom-default.xml")
                );
                String expectedDependencies = String.join(",", spec.getAllowedProjectDependencies().stream()
                        .map(path -> path.substring(1))
                        .sorted()
                        .toList());
                task.getExpectedFirstPartyDependencies().put(spec.getName(), expectedDependencies);
            });

            if (spec.isSpringIndependentPublication()) {
                verifyPublished.configure(task -> {
                    task.getSpringIndependentPublications().add(spec.getName());
                    if (spec.isAllExternalMainDependenciesDenied()) {
                        task.getDependencyFreePublication().set(spec.getName());
                    }
                });
            }
        });
    }

    private static void configureCycloneDx(Project project) {
        project.getPluginManager().apply("org.cyclonedx.bom");
        project.getTasks().named("cyclonedxBom", CyclonedxAggregateTask.class).configure(task -> {
            task.setGroup("verification");
            task.setDescription("Generates a reproducible aggregate CycloneDX SBOM for the repository.");
            task.getProjectType().set(Component.Type.LIBRARY);
            task.getComponentName().set(project.getName());
            task.getComponentVersion().set(project.getVersion().toString());
            task.getIncludeBomSerialNumber().set(false);
            task.getIncludeLicenseText().set(false);
            task.getIncludeBuildSystem().set(false);
            task.getJsonOutput().set(
                    project.getLayout().getBuildDirectory().file("reports/cyclonedx/bom.json")
            );
            task.getXmlOutput().unsetConvention();
            configureSbomIdentity(task);
            task.doLast(ignored -> normalizeReleaseSbom(
                    task.getJsonOutput().get().getAsFile()
            ));
        });
        // CycloneDX adds the direct BOM as an outgoing artifact when its task is
        // created. Realize these tasks before Gradle can consume that variant;
        // otherwise mixed task selections (for example POM generation + SBOM)
        // can observe the configuration first and make the plugin's mutation illegal.
        project.getTasks().named("cyclonedxDirectBom", CyclonedxDirectTask.class)
                .get()
                .setEnabled(false);
        for (Project module : project.getSubprojects()) {
            module.getTasks().named("cyclonedxDirectBom", CyclonedxDirectTask.class)
                    .get()
                    .setEnabled(false);
        }
    }

    private static void configureSbomIdentity(BaseCyclonedxTask task) {
        task.getLicenseChoice().set(apacheLicense());
        task.getExternalReferences().set(List.of(publicVcsReference()));
    }

    private static void normalizeReleaseSbom(File sbomFile) {
        try {
            Bom bom = new JsonParser().parse(sbomFile);
            Component rootComponent = bom.getMetadata().getComponent();
            rootComponent.setLicenses(apacheLicense());
            ensurePublicVcs(rootComponent);

            List<Component> firstPartyModules = bom.getComponents().stream()
                    .filter(component -> "io.github.ultramancode".equals(component.getGroup()))
                    .filter(component -> component.getName() != null
                            && component.getName().startsWith("spring-ai-privacy-guardrails-"))
                    .toList();
            if (firstPartyModules.size() != 8) {
                throw new GradleException(
                        "Release SBOM must contain exactly 8 first-party modules, found "
                                + firstPartyModules.size()
                );
            }
            for (Component component : firstPartyModules) {
                component.setLicenses(apacheLicense());
                ensurePublicVcs(component);
            }
            String normalized = BomGeneratorFactory.createJson(Version.VERSION_16, bom)
                    .toJsonString(true);
            Files.writeString(sbomFile.toPath(), normalized + "\n", StandardCharsets.UTF_8);
            List<org.cyclonedx.exception.ParseException> schemaViolations =
                    new JsonParser().validate(sbomFile, Version.VERSION_16);
            if (!schemaViolations.isEmpty()) {
                throw new GradleException(
                        "Normalized release SBOM failed CycloneDX 1.6 validation: "
                                + schemaViolations
                );
            }
        }
        catch (GradleException ex) {
            throw ex;
        }
        catch (Exception ex) {
            throw new GradleException("Unable to normalize the release SBOM", ex);
        }
    }

    private static LicenseChoice apacheLicense() {
        License license = new License();
        license.setId("Apache-2.0");
        license.setUrl("https://www.apache.org/licenses/LICENSE-2.0.txt");
        LicenseChoice licenses = new LicenseChoice();
        licenses.addLicense(license);
        return licenses;
    }

    private static void ensurePublicVcs(Component component) {
        String publicVcs = "https://github.com/ultramancode/spring-ai-privacy-guardrails";
        List<ExternalReference> references = component.getExternalReferences() == null
                ? new ArrayList<>()
                : new ArrayList<>(component.getExternalReferences());
        boolean alreadyPresent = references.stream().anyMatch(reference ->
                reference.getType() == ExternalReference.Type.VCS
                        && publicVcs.equals(reference.getUrl())
        );
        if (!alreadyPresent) {
            references.add(publicVcsReference());
            component.setExternalReferences(List.copyOf(references));
        }
    }

    private static ExternalReference publicVcsReference() {
        ExternalReference vcs = new ExternalReference();
        vcs.setType(ExternalReference.Type.VCS);
        vcs.setUrl("https://github.com/ultramancode/spring-ai-privacy-guardrails");
        return vcs;
    }
}
