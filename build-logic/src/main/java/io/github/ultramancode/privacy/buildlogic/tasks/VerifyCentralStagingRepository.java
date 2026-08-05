package io.github.ultramancode.privacy.buildlogic.tasks;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "This task validates staged signed artifacts and produces no output")
public abstract class VerifyCentralStagingRepository extends DefaultTask {

    private static final String PUBLIC_REPOSITORY_NAME = "spring-ai-privacy-guardrails";
    private static final String PRIVATE_REPOSITORY_IDENTITY = PUBLIC_REPOSITORY_NAME + "-private";

    private static final List<String> ARTIFACT_SUFFIXES = List.of(
            ".pom",
            ".module",
            ".jar",
            "-sources.jar",
            "-javadoc.jar"
    );

    private static final List<String> CHECKSUM_SUFFIXES = List.of(
            ".md5",
            ".sha1",
            ".sha256",
            ".sha512"
    );

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getRepositoryDirectory();

    @Input
    public abstract Property<String> getGroupPath();

    @Input
    public abstract Property<String> getReleaseVersion();

    @Input
    public abstract SetProperty<String> getPublishableProjects();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLicenseFile();

    @TaskAction
    public void verifyRepository() {
        File repository = getRepositoryDirectory().get().getAsFile();
        String version = getReleaseVersion().get();
        List<String> missing = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        byte[] expectedLicense;
        try {
            expectedLicense = Files.readAllBytes(getLicenseFile().get().getAsFile().toPath());
        }
        catch (IOException ex) {
            throw new GradleException("Unable to read the project license", ex);
        }
        for (String moduleName : getPublishableProjects().get().stream().sorted().toList()) {
            String basePath = getGroupPath().get() + "/" + moduleName + "/" + version
                    + "/" + moduleName + "-" + version;
            for (String artifactSuffix : ARTIFACT_SUFFIXES) {
                String artifactPath = basePath + artifactSuffix;
                require(repository, artifactPath, missing);
                require(repository, artifactPath + ".asc", missing);
                for (String checksum : CHECKSUM_SUFFIXES) {
                    require(repository, artifactPath + checksum, missing);
                    require(repository, artifactPath + ".asc" + checksum, missing);
                }
                if (artifactSuffix.endsWith(".jar")) {
                    verifyJarLicense(repository, artifactPath, expectedLicense, invalid);
                }
            }
            verifyPom(repository, basePath + ".pom", moduleName, version, invalid);
        }
        if (!missing.isEmpty()) {
            throw new GradleException(
                    "Central Portal bundle is incomplete; missing: " + String.join(", ", missing)
            );
        }
        if (!invalid.isEmpty()) {
            throw new GradleException(
                    "Central Portal bundle contains invalid publication metadata: "
                            + String.join(", ", invalid)
            );
        }
        getLogger().lifecycle(
                "Verified complete signed Central staging layout for {} publications.",
                getPublishableProjects().get().size()
        );
    }

    private static void require(File repository, String relativePath, List<String> missing) {
        if (!new File(repository, relativePath).isFile()) {
            missing.add(relativePath);
        }
    }

    private static void verifyJarLicense(
            File repository,
            String relativePath,
            byte[] expectedLicense,
            List<String> invalid
    ) {
        File jar = new File(repository, relativePath);
        if (!jar.isFile()) {
            return;
        }
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry licenseEntry = zip.getEntry("META-INF/LICENSE");
            if (licenseEntry == null
                    || !Arrays.equals(
                            expectedLicense,
                            zip.getInputStream(licenseEntry).readAllBytes()
                    )) {
                invalid.add(relativePath + " does not contain the exact root LICENSE");
            }
        }
        catch (IOException ex) {
            invalid.add(relativePath + " is not a readable JAR");
        }
    }

    private void verifyPom(
            File repository,
            String relativePath,
            String moduleName,
            String version,
            List<String> invalid
    ) {
        File pom = new File(repository, relativePath);
        if (!pom.isFile()) {
            return;
        }
        try {
            String xml = Files.readString(pom.toPath(), StandardCharsets.UTF_8);
            String groupId = getGroupPath().get().replace('/', '.');
            if (!xml.contains("<groupId>" + groupId + "</groupId>")
                    || !xml.contains("<artifactId>" + moduleName + "</artifactId>")
                    || !xml.contains("<version>" + version + "</version>")
                    || !xml.contains("<name>Apache License, Version 2.0</name>")
                    || !xml.contains("https://github.com/ultramancode/spring-ai-privacy-guardrails")
                    || xml.contains(PRIVATE_REPOSITORY_IDENTITY)) {
                invalid.add(relativePath + " has incomplete or private publication metadata");
            }
        }
        catch (IOException ex) {
            invalid.add(relativePath + " is not readable");
        }
    }
}
