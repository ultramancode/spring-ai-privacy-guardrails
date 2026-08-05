package io.github.ultramancode.privacy.buildlogic;

import java.io.File;
import java.util.Map;
import java.util.concurrent.Callable;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.plugins.signing.SigningExtension;

public final class PublishingConventionsPlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("privacy.java-conventions");
        ExtraPropertiesExtension extra = project.getExtensions().getExtraProperties();
        if (!extra.has("privacyAutomaticModuleName")) {
            throw new GradleException(
                    project.getPath() + " must declare an Automatic-Module-Name before applying publishing conventions"
            );
        }
        String automaticModuleName = extra.get("privacyAutomaticModuleName").toString();
        if (!extra.has("privacyPublicationDisplayName")) {
            throw new GradleException(
                    project.getPath() + " must declare a publication display name before applying publishing conventions"
            );
        }
        String publicationDisplayName = extra.get("privacyPublicationDisplayName").toString();

        project.getPluginManager().apply("maven-publish");
        project.getPluginManager().apply("signing");
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.withSourcesJar();
        java.withJavadocJar();
        project.getTasks().withType(Jar.class).configureEach(task ->
                task.from(
                        project.getRootProject().getLayout().getProjectDirectory().file("LICENSE"),
                        spec -> spec.into("META-INF").rename(ignored -> "LICENSE")
                )
        );
        project.getTasks().named("jar", Jar.class).configure(task ->
                task.getManifest().attributes(Map.of(
                        "Automatic-Module-Name",
                        automaticModuleName
                ))
        );

        PublishingExtension publishing = project.getExtensions().getByType(PublishingExtension.class);
        publishing.getRepositories().maven(repository -> {
            repository.setName("verification");
            repository.setUrl(project.uri(new File(project.getRootDir(), "build/verification-repository")));
        });
        publishing.getRepositories().maven(repository -> {
            repository.setName("centralStaging");
            repository.setUrl(project.uri(new File(project.getRootDir(), "build/central-staging-repository")));
        });
        MavenPublication publication = publishing.getPublications().create(
                "mavenJava",
                MavenPublication.class,
                maven -> {
                    maven.from(project.getComponents().getByName("java"));
                    maven.getPom().getName().set(publicationDisplayName);
                    maven.getPom().getDescription().set(project.getProviders().provider(() ->
                            project.getDescription() == null
                                    ? "Spring AI privacy guardrails module."
                                    : project.getDescription()
                    ));
                    maven.getPom().getUrl().set(
                            "https://github.com/ultramancode/spring-ai-privacy-guardrails"
                    );
                    maven.getPom().licenses(licenses -> licenses.license(license -> {
                        license.getName().set("Apache License, Version 2.0");
                        license.getUrl().set("https://www.apache.org/licenses/LICENSE-2.0.txt");
                        license.getDistribution().set("repo");
                    }));
                    maven.getPom().developers(developers -> developers.developer(developer -> {
                        developer.getId().set("ultramancode");
                        developer.getName().set("Taewoong Kim");
                        developer.getUrl().set("https://github.com/ultramancode");
                    }));
                    maven.getPom().scm(scm -> {
                        scm.getConnection().set(
                                "scm:git:https://github.com/ultramancode/spring-ai-privacy-guardrails.git"
                        );
                        scm.getDeveloperConnection().set(
                                "scm:git:ssh://git@github.com/ultramancode/spring-ai-privacy-guardrails.git"
                        );
                        scm.getUrl().set(
                                "https://github.com/ultramancode/spring-ai-privacy-guardrails"
                        );
                    });
                    maven.getPom().issueManagement(issueManagement -> {
                        issueManagement.getSystem().set("GitHub");
                        issueManagement.getUrl().set(
                                "https://github.com/ultramancode/spring-ai-privacy-guardrails/issues"
                        );
                    });
                }
        );

        SigningExtension signing = project.getExtensions().getByType(SigningExtension.class);
        String signingKey = project.getProviders().gradleProperty("signingKey").getOrNull();
        String signingPassword = project.getProviders().gradleProperty("signingPassword").getOrNull();
        if (signingKey != null) {
            signing.useInMemoryPgpKeys(signingKey, signingPassword);
        }
        signing.setRequired((Callable<Boolean>) () -> project.getGradle().getTaskGraph().getAllTasks()
                .stream()
                .anyMatch(task -> task.getName().equals("centralPortalBundle")
                        || task.getName().contains("CentralStagingRepository")));
        signing.sign(publication);
    }
}
