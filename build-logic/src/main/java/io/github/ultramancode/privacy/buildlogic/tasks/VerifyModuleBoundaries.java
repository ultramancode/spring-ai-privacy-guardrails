package io.github.ultramancode.privacy.buildlogic.tasks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "This task validates declared architecture and produces no output")
public abstract class VerifyModuleBoundaries extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getModuleMetadataFiles();

    @Input
    public abstract SetProperty<String> getConfiguredProjectPaths();

    @Input
    public abstract MapProperty<String, String> getAllowedProjectDependencies();

    @Input
    public abstract SetProperty<String> getProjectsWithoutExternalMainDependencies();

    @Input
    public abstract MapProperty<String, String> getForbiddenExternalGroupPrefixes();

    @TaskAction
    public void verifyBoundaries() throws IOException {
        Map<String, ModuleMetadata> metadataByPath = new LinkedHashMap<>();
        for (File metadataFile : getModuleMetadataFiles().getFiles()) {
            ModuleMetadata metadata = readMetadata(metadataFile);
            ModuleMetadata previous = metadataByPath.put(metadata.modulePath(), metadata);
            if (previous != null) {
                throw new GradleException("Duplicate module metadata for " + metadata.modulePath());
            }
        }

        Set<String> configuredPaths = new TreeSet<>(getConfiguredProjectPaths().get());
        Set<String> allowedPaths = new TreeSet<>(getAllowedProjectDependencies().get().keySet());
        List<String> violations = new ArrayList<>();

        Set<String> unclassified = new TreeSet<>(configuredPaths);
        unclassified.removeAll(allowedPaths);
        if (!unclassified.isEmpty()) {
            violations.add("Unclassified Gradle modules: " + unclassified);
        }

        Set<String> missingProjects = new TreeSet<>(allowedPaths);
        missingProjects.removeAll(configuredPaths);
        if (!missingProjects.isEmpty()) {
            violations.add("Architecture policy references missing Gradle modules: " + missingProjects);
        }

        for (Map.Entry<String, String> entry : getAllowedProjectDependencies().get().entrySet()) {
            String modulePath = entry.getKey();
            ModuleMetadata metadata = metadataByPath.get(modulePath);
            if (metadata == null) {
                violations.add("Missing dependency metadata for " + modulePath);
                continue;
            }
            Set<String> allowed = parseCommaSeparatedSet(entry.getValue());
            if (!metadata.projectDependencies().equals(allowed)) {
                violations.add(modulePath + " project dependencies must be " + sortedValues(allowed)
                        + ", but were " + sortedValues(metadata.projectDependencies()));
            }
        }

        for (String modulePath : getProjectsWithoutExternalMainDependencies().get()) {
            ModuleMetadata metadata = metadataByPath.get(modulePath);
            if (metadata != null && !metadata.externalDependencies().isEmpty()) {
                violations.add(modulePath + " must have no external main dependencies, but found "
                        + sortedValues(metadata.externalDependencies()));
            }
        }

        for (Map.Entry<String, String> entry : getForbiddenExternalGroupPrefixes().get().entrySet()) {
            ModuleMetadata metadata = metadataByPath.get(entry.getKey());
            if (metadata == null) {
                continue;
            }
            Set<String> prefixes = parseCommaSeparatedSet(entry.getValue());
            Set<String> forbidden = metadata.externalDependencies().stream()
                    .filter(coordinate -> prefixes.stream()
                            .anyMatch(prefix -> dependencyGroup(coordinate).startsWith(prefix)))
                    .collect(Collectors.toCollection(TreeSet::new));
            if (!forbidden.isEmpty()) {
                violations.add(entry.getKey() + " must not depend on external groups "
                        + sortedValues(prefixes) + ", but found " + forbidden);
            }
        }

        if (!violations.isEmpty()) {
            throw new GradleException("Module boundary violations:\n - " + String.join("\n - ", violations));
        }
        getLogger().lifecycle("Verified {} classified Gradle module boundaries.", configuredPaths.size());
    }

    private static ModuleMetadata readMetadata(File file) throws IOException {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            int separator = line.indexOf('=');
            if (separator > 0) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        String modulePath = values.get("modulePath");
        if (modulePath == null || modulePath.isBlank()) {
            throw new GradleException("Invalid module metadata without modulePath: " + file);
        }
        return new ModuleMetadata(
                modulePath,
                parseCommaSeparatedSet(values.getOrDefault("projectDependencies", "")),
                parseCommaSeparatedSet(values.getOrDefault("externalDependencies", ""))
        );
    }

    private static Set<String> parseCommaSeparatedSet(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String dependencyGroup(String coordinate) {
        int separator = coordinate.indexOf(':');
        return separator < 0 ? coordinate : coordinate.substring(0, separator);
    }

    private static List<String> sortedValues(Set<String> values) {
        return new ArrayList<>(new TreeSet<>(values));
    }

    private record ModuleMetadata(
            String modulePath,
            Set<String> projectDependencies,
            Set<String> externalDependencies
    ) {
    }
}
