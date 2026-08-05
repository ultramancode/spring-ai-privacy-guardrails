package io.github.ultramancode.privacy.buildlogic.snapshot;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.LocalState;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Uses Git to inspect the current worktree and always refreshes the public candidate")
public abstract class PreparePublicSnapshot extends DefaultTask {

    private static final Pattern ALLOWED_IMAGE = Pattern.compile(
            "docs/images/[^/]+\\.(?i:png|jpe?g|gif|webp|ico)"
    );

    private static final Pattern SUSPICIOUS_BINARY_EXTENSION = Pattern.compile(
            "(?i).*\\.(?:jar|zip|tar|tgz|gz|bz2|xz|7z|rar|pdf|mp4|mov|avi|class|bin|exe|dll|so|dylib|woff2?|ttf|otf)"
    );

    private static final Set<String> WORKSPACE_SEGMENTS = Set.of(
            ".idea",
            ".vscode",
            ".settings",
            ".DS_Store",
            ".classpath",
            ".factorypath",
            ".project"
    );

    @Internal
    public abstract DirectoryProperty getRootDirectory();

    @Internal
    public abstract DirectoryProperty getWorkspaceDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getSnapshotTreeDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getDotfileStagingDirectory();

    @OutputFile
    public abstract RegularFileProperty getSourceArchive();

    @LocalState
    public abstract RegularFileProperty getTemporaryIndexFile();

    @Optional
    @Input
    public abstract Property<String> getSnapshotRef();

    @Input
    public abstract ListProperty<String> getRequiredPublicPaths();

    @Input
    public abstract ListProperty<String> getExecutablePublicPaths();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getPolicyFiles();

    @TaskAction
    public void prepareSnapshot() throws IOException {
        File rootDirectory = getRootDirectory().get().getAsFile();
        File workspaceDirectory = getWorkspaceDirectory().get().getAsFile();
        cleanDirectory(workspaceDirectory.toPath());
        Files.createDirectories(workspaceDirectory.toPath());

        List<File> policyFiles = getPolicyFiles().getFiles().stream()
                .sorted()
                .toList();
        if (policyFiles.size() > 1) {
            throw new GradleException("Multiple public snapshot policies found: " + policyFiles);
        }
        if (new File(rootDirectory, ".private").isDirectory() && policyFiles.size() != 1) {
            throw new GradleException(
                    "A private workbench must provide exactly one public snapshot policy"
            );
        }
        Properties policy = loadPolicy(policyFiles);
        List<String> forbiddenPaths = Stream.concat(
                        Stream.of(".private", "AGENTS.md"),
                        policyValues(policy, "forbiddenPath").stream()
                )
                .map(PreparePublicSnapshot::normalizeForbiddenPath)
                .distinct()
                .toList();
        List<Pattern> policyTextPatterns = policyValues(policy, "forbiddenTextRegex").stream()
                .map(Pattern::compile)
                .toList();
        Set<String> attributeLinesToRemove = Set.copyOf(policyValues(policy, "gitattributesRemove"));

        File temporaryIndex = getTemporaryIndexFile().get().getAsFile();
        File sourceArchive = getSourceArchive().get().getAsFile();
        String treeish;
        Set<String> indexedExecutablePaths;
        String configuredRef = getSnapshotRef().getOrNull();
        try {
            if (configuredRef != null) {
                treeish = runGit(
                        rootDirectory,
                        List.of("rev-parse", "--verify", configuredRef + "^{tree}"),
                        Map.of()
                );
            }
            else {
                Files.deleteIfExists(temporaryIndex.toPath());
                Map<String, String> indexEnvironment = Map.of(
                        "GIT_INDEX_FILE",
                        temporaryIndex.getAbsolutePath()
                );
                String stagedTree = runGit(rootDirectory, List.of("write-tree"), Map.of());
                runGit(rootDirectory, List.of("read-tree", stagedTree), indexEnvironment);
                runGit(rootDirectory, List.of("add", "-A", "--", "."), indexEnvironment);
                treeish = runGit(rootDirectory, List.of("write-tree"), indexEnvironment);
            }
            indexedExecutablePaths = executablePaths(runGit(
                    rootDirectory,
                    List.of("ls-tree", "-r", "-z", treeish),
                    Map.of()
            ));

            runGit(
                    rootDirectory,
                    List.of(
                            "archive",
                            "--format=zip",
                            "--output=" + sourceArchive.getAbsolutePath(),
                            treeish
                    ),
                    Map.of()
            );
        }
        finally {
            Files.deleteIfExists(temporaryIndex.toPath());
        }

        File extracted = getSnapshotTreeDirectory().get().getAsFile();
        Files.createDirectories(extracted.toPath());
        List<String> archivePaths = extractArchive(sourceArchive, extracted);
        List<String> violations = new ArrayList<>();

        for (String forbiddenPath : forbiddenPaths) {
            archivePaths.stream()
                    .filter(path -> path.equals(forbiddenPath) || path.startsWith(forbiddenPath + "/"))
                    .forEach(path -> violations.add("policy-forbidden path was exported: " + path));
        }

        sanitizeAttributes(extracted, attributeLinesToRemove);
        stageRootDotfiles(extracted, getDotfileStagingDirectory().get().getAsFile());

        for (String requiredPath : getRequiredPublicPaths().get()) {
            if (!archivePaths.contains(requiredPath)) {
                violations.add("required public file is missing: " + requiredPath);
            }
        }
        Set<String> configuredExecutablePaths = Set.copyOf(getExecutablePublicPaths().get());
        if (!indexedExecutablePaths.equals(configuredExecutablePaths)) {
            Set<String> unconfigured = new TreeSet<>(indexedExecutablePaths);
            unconfigured.removeAll(configuredExecutablePaths);
            Set<String> incorrectlyConfigured = new TreeSet<>(configuredExecutablePaths);
            incorrectlyConfigured.removeAll(indexedExecutablePaths);
            if (!unconfigured.isEmpty()) {
                violations.add("indexed executable path is not preserved: " + unconfigured);
            }
            if (!incorrectlyConfigured.isEmpty()) {
                violations.add("configured executable path is not mode 100755: " + incorrectlyConfigured);
            }
        }

        for (String path : archivePaths) {
            if (isWorkspacePath(path)) {
                violations.add("workspace-only path was exported: " + path);
            }
        }

        inspectExtractedTree(extracted, policyTextPatterns, violations);

        if (!violations.isEmpty()) {
            throw new GradleException(
                    "Public snapshot verification failed:\n - "
                            + String.join("\n - ", new TreeSet<>(violations))
            );
        }
        getLogger().lifecycle(
                "Verified sanitized public tree with {} source archive entries.",
                archivePaths.size()
        );
    }

    private static Set<String> executablePaths(String treeEntries) {
        Set<String> executablePaths = new TreeSet<>();
        // git ls-tree -z uses NUL rather than newline as the record delimiter.
        for (String entry : treeEntries.split("\0")) {
            if (!entry.startsWith("100755 ")) {
                continue;
            }
            int pathSeparator = entry.indexOf('\t');
            if (pathSeparator < 0 || pathSeparator == entry.length() - 1) {
                throw new GradleException("Git returned an invalid executable tree entry");
            }
            executablePaths.add(entry.substring(pathSeparator + 1));
        }
        return Set.copyOf(executablePaths);
    }

    private static Properties loadPolicy(List<File> policyFiles) throws IOException {
        Properties policy = new Properties();
        if (!policyFiles.isEmpty()) {
            try (BufferedReader reader = Files.newBufferedReader(
                    policyFiles.get(0).toPath(),
                    StandardCharsets.UTF_8
            )) {
                policy.load(reader);
            }
        }
        return policy;
    }

    private static List<String> policyValues(Properties policy, String prefix) {
        return policy.stringPropertyNames().stream()
                .filter(key -> key.startsWith(prefix + "."))
                .sorted()
                .map(policy::getProperty)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    private static String normalizeForbiddenPath(String path) {
        return path.replace('\\', '/')
                .replaceFirst("^\\./", "")
                .replaceFirst("/$", "");
    }

    private static String runGit(
            File rootDirectory,
            List<String> arguments,
            Map<String, String> environment
    ) throws IOException {
        List<String> command = new ArrayList<>(arguments.size() + 1);
        command.add("git");
        command.addAll(arguments);
        ProcessBuilder processBuilder = new ProcessBuilder(command)
                .directory(rootDirectory)
                .redirectErrorStream(true);
        processBuilder.environment().putAll(environment);
        Process process = processBuilder.start();
        String gitOutput;
        try (InputStream input = process.getInputStream()) {
            gitOutput = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GradleException("Interrupted while running git " + String.join(" ", arguments), ex);
        }
        if (exitCode != 0) {
            throw new GradleException(
                    "git " + String.join(" ", arguments) + " failed (" + exitCode + "): " + gitOutput.trim()
            );
        }
        return gitOutput.trim();
    }

    private static List<String> extractArchive(File archive, File extracted) throws IOException {
        List<String> archivePaths = new ArrayList<>();
        Path extractionRoot = extracted.getCanonicalFile().toPath();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String path = entry.getName().replace('\\', '/');
                if (path.startsWith("/") || List.of(path.split("/")).contains("..")) {
                    throw new GradleException("Public archive contains an unsafe path: " + path);
                }
                archivePaths.add(path);
                File destination = new File(extracted, path).getCanonicalFile();
                if (!destination.toPath().startsWith(extractionRoot)) {
                    throw new GradleException("Public archive escapes its extraction root: " + path);
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(destination.toPath());
                }
                else {
                    Files.createDirectories(destination.toPath().getParent());
                    try (InputStream input = zip.getInputStream(entry)) {
                        Files.copy(input, destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        return archivePaths;
    }

    private static void sanitizeAttributes(File extracted, Set<String> attributeLinesToRemove)
            throws IOException {
        File attributesFile = new File(extracted, ".gitattributes");
        if (!attributesFile.isFile() || attributeLinesToRemove.isEmpty()) {
            return;
        }
        List<String> sanitizedLines = Files.readAllLines(attributesFile.toPath(), StandardCharsets.UTF_8)
                .stream()
                .filter(line -> !attributeLinesToRemove.contains(line))
                .toList();
        Files.writeString(
                attributesFile.toPath(),
                String.join("\n", sanitizedLines) + "\n",
                StandardCharsets.UTF_8
        );
    }

    private static void stageRootDotfiles(File extracted, File stagingDirectory) throws IOException {
        Files.createDirectories(stagingDirectory.toPath());
        for (String name : List.of(".gitattributes", ".gitignore")) {
            File sourceFile = new File(extracted, name);
            if (sourceFile.isFile()) {
                Files.copy(
                        sourceFile.toPath(),
                        new File(stagingDirectory, name.substring(1)).toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        }
    }

    private static boolean isWorkspacePath(String path) {
        for (String segment : path.split("/")) {
            if (WORKSPACE_SEGMENTS.contains(segment)
                    || segment.equals(".env")
                    || (segment.startsWith(".env.") && !segment.equals(".env.example"))) {
                return true;
            }
        }
        return path.endsWith(".iml");
    }

    private static void inspectExtractedTree(
            File extracted,
            List<Pattern> policyTextPatterns,
            List<String> violations
    ) throws IOException {
        Map<String, Pattern> forbiddenText = forbiddenTextPatterns(policyTextPatterns);

        List<Pattern> linkPatterns = List.of(
                Pattern.compile("(?m)!?\\[[^]]*]\\(([^)]+)\\)"),
                Pattern.compile("(?m)^\\s*\\[[^]]+]:\\s*(\\S+)"),
                Pattern.compile("(?i)(?:src|href)=[\"']([^\"']+)[\"']")
        );

        try (Stream<Path> files = Files.walk(extracted.toPath())) {
            files.filter(Files::isRegularFile).forEach(path -> {
                try {
                    inspectFile(extracted, path.toFile(), forbiddenText, linkPatterns, violations);
                }
                catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
        catch (UncheckedIOException ex) {
            throw ex.getCause();
        }
    }

    static Map<String, Pattern> forbiddenTextPatterns(List<Pattern> policyTextPatterns) {
        Map<String, Pattern> forbiddenText = new LinkedHashMap<>();
        forbiddenText.put(
                "local macOS path",
                Pattern.compile("/" + "Users/" + "[^\\s\"'<>]+")
        );
        forbiddenText.put(
                "local temporary path",
                Pattern.compile("/" + "var/folders/" + "[^\\s\"'<>]+")
        );
        forbiddenText.put(
                "workspace attachment",
                Pattern.compile("(?i)\\." + "codex/attachments/")
        );
        forbiddenText.put(
                "private key",
                Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----")
        );
        forbiddenText.put(
                "GitHub credential",
                Pattern.compile("(?i)(?:github_pat_|gh[pousr]_)[A-Za-z0-9_]{20,}")
        );
        forbiddenText.put("AWS access key", Pattern.compile("AKIA[0-9A-Z]{16}"));
        forbiddenText.put("Google API key", Pattern.compile("AIza[0-9A-Za-z_-]{30,}"));
        forbiddenText.put(
                "OpenAI credential",
                Pattern.compile("sk-(?:admin|proj|svcacct)-[A-Za-z0-9_-]{20,}")
        );
        forbiddenText.put(
                "Anthropic credential",
                Pattern.compile("sk-ant-(?:api|admin)[0-9]{2}-[A-Za-z0-9_-]{20,}")
        );
        forbiddenText.put("Slack credential", Pattern.compile("xox[baprs]-[0-9A-Za-z-]{20,}"));
        forbiddenText.put(
                "literal bearer token",
                Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._-]{24,}")
        );
        for (int index = 0; index < policyTextPatterns.size(); index++) {
            forbiddenText.put("policy-forbidden text " + (index + 1), policyTextPatterns.get(index));
        }
        return Map.copyOf(forbiddenText);
    }

    private static void inspectFile(
            File extracted,
            File file,
            Map<String, Pattern> forbiddenText,
            List<Pattern> linkPatterns,
            List<String> violations
    ) throws IOException {
        String relativePath = extracted.toPath().relativize(file.toPath()).toString().replace('\\', '/');
        byte[] content = Files.readAllBytes(file.toPath());
        boolean containsNul = false;
        for (byte value : content) {
            if (value == 0) {
                containsNul = true;
                break;
            }
        }
        if (containsNul || SUSPICIOUS_BINARY_EXTENSION.matcher(relativePath).matches()) {
            if (!isAllowedBinary(relativePath)) {
                violations.add("unexpected binary file: " + relativePath);
            }
            return;
        }

        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        }
        catch (CharacterCodingException ex) {
            if (!isAllowedBinary(relativePath)) {
                violations.add("unexpected binary file: " + relativePath);
            }
            return;
        }

        forbiddenText.forEach((label, pattern) -> {
            if (pattern.matcher(text).find()) {
                violations.add(label + " found in " + relativePath);
            }
        });

        if (!relativePath.endsWith(".md")) {
            return;
        }
        inspectLocalLinks(extracted, file, relativePath, text, linkPatterns, violations);
    }

    private static boolean isAllowedBinary(String path) {
        return path.equals("gradle/wrapper/gradle-wrapper.jar") || ALLOWED_IMAGE.matcher(path).matches();
    }

    private static void inspectLocalLinks(
            File extracted,
            File sourceFile,
            String relativePath,
            String text,
            List<Pattern> linkPatterns,
            List<String> violations
    ) throws IOException {
        Path extractionRoot = extracted.getCanonicalFile().toPath();
        for (Pattern pattern : linkPatterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                String target = matcher.group(1).trim();
                if (target.startsWith("<") && target.contains(">")) {
                    target = target.substring(1, target.indexOf('>'));
                }
                else {
                    target = target.split("\\s+", 2)[0];
                }
                if (target.isBlank()
                        || target.startsWith("#")
                        || target.matches("(?i)(?:https?|mailto|data):.*")
                        || target.contains("${")) {
                    continue;
                }
                target = target.replace("%20", " ");
                int fragment = target.indexOf('#');
                if (fragment >= 0) {
                    target = target.substring(0, fragment);
                }
                int query = target.indexOf('?');
                if (query >= 0) {
                    target = target.substring(0, query);
                }
                if (target.isBlank()) {
                    continue;
                }
                if (target.startsWith("/") || target.matches("^[A-Za-z]:[\\\\/].*")) {
                    violations.add("absolute local Markdown link in " + relativePath + ": " + target);
                    continue;
                }
                File linked = new File(sourceFile.getParentFile(), target).getCanonicalFile();
                if (!linked.toPath().startsWith(extractionRoot) || !linked.exists()) {
                    violations.add("broken local Markdown link in " + relativePath + ": " + target);
                }
            }
        }
    }

    private static void cleanDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                }
                catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
        catch (UncheckedIOException ex) {
            throw new GradleException("Unable to clean public snapshot directory: " + directory, ex.getCause());
        }
    }
}
