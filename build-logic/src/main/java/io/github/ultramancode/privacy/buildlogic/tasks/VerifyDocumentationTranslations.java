package io.github.ultramancode.privacy.buildlogic.tasks;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "This task validates synchronized documentation and produces no output")
public abstract class VerifyDocumentationTranslations extends DefaultTask {

    @Internal
    public abstract DirectoryProperty getRepositoryDirectory();

    @Input
    public abstract MapProperty<String, String> getLocalizedDocuments();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getDocumentFiles();

    @TaskAction
    public void verifyTranslations() throws IOException {
        File repository = getRepositoryDirectory().get().getAsFile();
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : getLocalizedDocuments().get().entrySet()) {
            String sourcePath = entry.getKey();
            String targetPath = entry.getValue();
            File sourceFile = new File(repository, sourcePath);
            File targetFile = new File(repository, targetPath);
            if (!sourceFile.isFile()) {
                violations.add("Missing localization source: " + sourcePath);
                continue;
            }
            if (!targetFile.isFile()) {
                violations.add("Missing Korean translation: " + targetPath);
                continue;
            }

            List<String> lines = Files.readAllLines(targetFile.toPath(), StandardCharsets.UTF_8);
            List<String> sourceMarkers = lines.stream()
                    .filter(line -> line.startsWith("<!-- i18n-source:"))
                    .toList();
            String expectedSourceMarker = "<!-- i18n-source: " + sourcePath + " -->";
            if (!sourceMarkers.equals(List.of(expectedSourceMarker))) {
                violations.add(targetPath + " must contain exactly '" + expectedSourceMarker + "'");
            }

            List<String> hashMarkers = lines.stream()
                    .filter(line -> line.startsWith("<!-- i18n-source-sha256:"))
                    .toList();
            String expectedHashMarker = "<!-- i18n-source-sha256: " + sha256(sourceFile) + " -->";
            if (!hashMarkers.equals(List.of(expectedHashMarker))) {
                String found = hashMarkers.isEmpty() ? "missing" : String.join(", ", hashMarkers);
                violations.add(targetPath + " is stale for " + sourcePath + "; expected '"
                        + expectedHashMarker + "', found '" + found
                        + "'. Review the translation before updating its hash.");
            }

            String translation = Files.readString(targetFile.toPath(), StandardCharsets.UTF_8);
            if (translation.contains(".private/")) {
                violations.add(targetPath + " must not link to private workspace material");
            }
        }

        if (!violations.isEmpty()) {
            throw new GradleException(
                    "Documentation translation verification failed:\n - "
                            + String.join("\n - ", violations)
            );
        }
        getLogger().lifecycle(
                "Verified {} synchronized Korean document translations.",
                getLocalizedDocuments().get().size()
        );
    }

    private static String sha256(File file) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
