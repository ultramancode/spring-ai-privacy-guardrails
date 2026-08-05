package io.github.ultramancode.privacy.buildlogic.tasks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.TreeSet;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

@CacheableTask
public abstract class WriteModuleMetadata extends DefaultTask {

    @Input
    public abstract Property<String> getModulePath();

    @Input
    public abstract ListProperty<String> getProjectDependencies();

    @Input
    public abstract ListProperty<String> getExternalDependencies();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void writeMetadata() throws IOException {
        String content = "modulePath=" + getModulePath().get() + "\n"
                + "projectDependencies=" + String.join(",", new TreeSet<>(getProjectDependencies().get())) + "\n"
                + "externalDependencies=" + String.join(",", new TreeSet<>(getExternalDependencies().get())) + "\n";
        Files.createDirectories(getOutputFile().get().getAsFile().toPath().getParent());
        Files.writeString(
                getOutputFile().get().getAsFile().toPath(),
                content,
                StandardCharsets.UTF_8
        );
    }
}
