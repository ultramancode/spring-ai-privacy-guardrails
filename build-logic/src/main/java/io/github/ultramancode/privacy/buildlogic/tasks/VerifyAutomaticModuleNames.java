package io.github.ultramancode.privacy.buildlogic.tasks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "This task validates JAR manifests and JPMS resolution and produces no output")
public abstract class VerifyAutomaticModuleNames extends DefaultTask {

    @Input
    public abstract MapProperty<String, String> getExpectedAutomaticModuleNames();

    @Input
    public abstract Property<String> getProjectVersion();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getJarFiles();

    @TaskAction
    public void verifyModules() throws IOException, InterruptedException {
        Map<String, String> expectedNames = getExpectedAutomaticModuleNames().get();
        Map<String, File> jarsByModule = locateJars(
                expectedNames.keySet(),
                getProjectVersion().get(),
                getJarFiles().getFiles()
        );
        Set<String> actualNames = new HashSet<>();
        List<String> violations = new ArrayList<>();

        for (Map.Entry<String, String> entry : expectedNames.entrySet()) {
            String moduleName = entry.getKey();
            String expectedName = entry.getValue();
            File jarFile = jarsByModule.get(moduleName);
            if (jarFile == null) {
                violations.add("Missing JAR for " + moduleName);
                continue;
            }
            Set<String> classPackages = new TreeSet<>();
            String actualName;
            try (JarFile jar = new JarFile(jarFile)) {
                actualName = jar.getManifest() == null
                        ? null
                        : jar.getManifest().getMainAttributes().getValue("Automatic-Module-Name");
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String entryName = entries.nextElement().getName();
                    if (!entryName.endsWith(".class")) {
                        continue;
                    }
                    String classPath = entryName.replaceFirst("^META-INF/versions/\\d+/", "");
                    if (classPath.equals("module-info.class")) {
                        continue;
                    }
                    int separator = classPath.lastIndexOf('/');
                    classPackages.add(separator < 0
                            ? ""
                            : classPath.substring(0, separator).replace('/', '.'));
                }
            }
            if (!expectedName.equals(actualName)) {
                violations.add(moduleName + " must declare Automatic-Module-Name " + expectedName
                        + ", found " + actualName);
            }
            if (actualName != null && !actualNames.add(actualName)) {
                violations.add("Duplicate Automatic-Module-Name " + actualName);
            }
            if (!classPackages.equals(Set.of(expectedName))) {
                violations.add(moduleName + " classes must remain in the single package " + expectedName
                        + ", found " + classPackages);
            }
        }

        if (!violations.isEmpty()) {
            throw new GradleException(
                    "Automatic module verification failed:\n - " + String.join("\n - ", violations)
            );
        }
        validateModulePath(jarsByModule.values());
        getLogger().lifecycle(
                "Verified {} published automatic modules and single-package JARs.",
                actualNames.size()
        );
    }

    private static Map<String, File> locateJars(
            Set<String> moduleNames,
            String projectVersion,
            Set<File> jarFiles
    ) {
        Map<String, File> jarsByModuleName = new HashMap<>();
        for (String moduleName : moduleNames) {
            List<File> matches = jarFiles.stream()
                    .filter(file -> file.getName().equals(
                            moduleName + "-" + projectVersion + ".jar"
                    ))
                    .toList();
            if (matches.size() == 1) {
                jarsByModuleName.put(moduleName, matches.get(0));
            } else if (matches.size() > 1) {
                throw new GradleException("Multiple main JARs found for " + moduleName + ": " + matches);
            }
        }
        return jarsByModuleName;
    }

    private static void validateModulePath(Iterable<File> jarFiles) throws IOException, InterruptedException {
        List<String> paths = new ArrayList<>();
        jarFiles.forEach(file -> paths.add(file.getAbsolutePath()));
        File javaExecutable = new File(System.getProperty("java.home"), "bin/java");
        Process validation = new ProcessBuilder(
                javaExecutable.getAbsolutePath(),
                "--validate-modules",
                "--module-path",
                String.join(File.pathSeparator, paths)
        ).redirectErrorStream(true).start();
        String validationOutput = new String(
                validation.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
        );
        int exitCode = validation.waitFor();
        if (exitCode != 0) {
            throw new GradleException(
                    "Published automatic modules fail JPMS validation: " + validationOutput.trim()
            );
        }
    }
}
