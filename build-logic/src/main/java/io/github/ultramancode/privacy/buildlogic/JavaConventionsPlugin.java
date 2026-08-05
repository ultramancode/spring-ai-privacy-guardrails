package io.github.ultramancode.privacy.buildlogic;

import java.util.List;

import io.github.ultramancode.privacy.buildlogic.tasks.WriteModuleMetadata;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.gradle.external.javadoc.StandardJavadocDocletOptions;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

public final class JavaConventionsPlugin implements Plugin<Project> {

    private static final List<String> MAIN_DEPENDENCY_CONFIGURATIONS = List.of(
            "api",
            "implementation",
            "compileOnly",
            "compileOnlyApi",
            "runtimeOnly"
    );

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("java-library");
        project.setGroup(project.getProviders().gradleProperty("group").get());
        project.setVersion(project.getProviders().gradleProperty("version").get());

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().set(
                project.getProviders().gradleProperty("testJavaVersion")
                        .orElse(project.getProviders().gradleProperty("javaVersion"))
                        .map(version -> JavaLanguageVersion.of(Integer.parseInt(version)))
        );

        project.getDependencies().add(
                "testImplementation",
                "org.junit.jupiter:junit-jupiter:"
                        + project.getProviders().gradleProperty("junitVersion").get()
        );
        project.getDependencies().add(
                "testImplementation",
                "org.assertj:assertj-core:"
                        + project.getProviders().gradleProperty("assertjVersion").get()
        );
        project.getDependencies().add(
                "testRuntimeOnly",
                "org.junit.platform:junit-platform-launcher:"
                        + project.getProviders().gradleProperty("junitVersion").get()
        );

        project.getTasks().withType(JavaCompile.class).configureEach(task -> {
            task.getOptions().setEncoding("UTF-8");
            task.getOptions().getRelease().set(
                    project.getProviders().gradleProperty("javaVersion").map(Integer::parseInt)
            );
            task.getOptions().getCompilerArgs().add("-parameters");
        });
        project.getTasks().withType(Test.class).configureEach(Test::useJUnitPlatform);
        project.getTasks().withType(Javadoc.class).configureEach(task -> {
            StandardJavadocDocletOptions options = (StandardJavadocDocletOptions) task.getOptions();
            options.addStringOption("Xdoclint:all,-missing", "-quiet");
        });
        project.getTasks().withType(AbstractArchiveTask.class).configureEach(task -> {
            task.setPreserveFileTimestamps(false);
            task.setReproducibleFileOrder(true);
        });

        TaskProvider<WriteModuleMetadata> metadata = project.getTasks().register(
                "writePrivacyModuleMetadata",
                WriteModuleMetadata.class,
                task -> {
                    task.setGroup("verification");
                    task.setDescription(
                            "Writes declared main dependencies for repository architecture verification."
                    );
                    task.getModulePath().set(project.getPath());
                    task.getProjectDependencies().convention(List.of());
                    task.getExternalDependencies().convention(List.of());
                    task.getOutputFile().set(
                            project.getLayout().getBuildDirectory()
                                    .file("privacy-verification/module-metadata.properties")
                    );
                }
        );
        for (String configurationName : MAIN_DEPENDENCY_CONFIGURATIONS) {
            project.getConfigurations().named(configurationName).configure(configuration ->
                    configuration.getDependencies().all(dependency -> metadata.configure(task -> {
                        if (dependency instanceof ProjectDependency projectDependency) {
                            task.getProjectDependencies().add(projectDependency.getPath());
                        } else {
                            String group = dependency.getGroup() == null
                                    ? "<unscoped>"
                                    : dependency.getGroup();
                            task.getExternalDependencies().add(
                                    group + ":" + dependency.getName()
                            );
                        }
                    }))
            );
        }
    }
}
