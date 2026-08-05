package io.github.ultramancode.privacy.buildlogic.snapshot;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.gradle.api.GradleException;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.bundling.Zip;

public abstract class VerifyPublicSnapshotArchive extends Zip {

    private static final int ZIP16_MAX_VALUE = 0xffff;
    private static final long ZIP32_MAX_VALUE = 0xffff_ffffL;
    private static final int CENTRAL_DIRECTORY_HEADER_SIGNATURE = 0x02014b50;
    private static final int CENTRAL_DIRECTORY_HEADER_BYTES = 46;
    private static final int END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50;
    private static final int END_OF_CENTRAL_DIRECTORY_BYTES = 22;
    private static final int UNIX_MODE_SHIFT = 16;

    @Input
    public abstract ListProperty<String> getRequiredPublicPaths();

    @Input
    public abstract ListProperty<String> getExecutablePublicPaths();

    @Override
    @TaskAction
    protected void copy() {
        super.copy();
        File archive = getArchiveFile().get().getAsFile();
        List<String> archiveEntries = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> zipEntries = zip.entries();
            while (zipEntries.hasMoreElements()) {
                archiveEntries.add(zipEntries.nextElement().getName());
            }
        }
        catch (IOException ex) {
            throw new GradleException("Unable to inspect public snapshot archive: " + archive, ex);
        }
        List<String> missing = getRequiredPublicPaths().get().stream()
                .filter(path -> !archiveEntries.contains(path))
                .toList();
        if (!missing.isEmpty()) {
            throw new GradleException("Reproducible public archive is missing: " + String.join(", ", missing));
        }
        Map<String, Integer> unixModes = readUnixModes(archive);
        List<String> nonExecutable = getExecutablePublicPaths().get().stream()
                .filter(path -> (unixModes.getOrDefault(path, 0) & 0111) == 0)
                .toList();
        if (!nonExecutable.isEmpty()) {
            throw new GradleException(
                    "Reproducible public archive lost executable modes: "
                            + String.join(", ", nonExecutable)
            );
        }
        getLogger().lifecycle(
                "Created reproducible public snapshot {} ({} entries).",
                archive,
                archiveEntries.size()
        );
    }

    private static Map<String, Integer> readUnixModes(File archive) {
        // java.util.zip does not expose the upper external-attribute bits that carry UNIX modes.
        byte[] content;
        try {
            content = Files.readAllBytes(archive.toPath());
        }
        catch (IOException ex) {
            throw new GradleException("Unable to read public snapshot archive modes", ex);
        }
        Map<String, Integer> modes = new HashMap<>();
        int endOfCentralDirectory = findEndOfCentralDirectory(content);
        int entryCount = littleEndianShort(content, endOfCentralDirectory + 10);
        long centralDirectoryOffset = Integer.toUnsignedLong(
                littleEndianInt(content, endOfCentralDirectory + 16)
        );
        if (entryCount == ZIP16_MAX_VALUE
                || centralDirectoryOffset == ZIP32_MAX_VALUE
                || centralDirectoryOffset > content.length) {
            throw new GradleException("Public snapshot ZIP64 archives are not supported");
        }
        int offset = (int) centralDirectoryOffset;
        for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
            if (offset + CENTRAL_DIRECTORY_HEADER_BYTES > content.length
                    || littleEndianInt(content, offset) != CENTRAL_DIRECTORY_HEADER_SIGNATURE) {
                throw new GradleException("Public snapshot ZIP has an invalid central directory");
            }
            int nameLength = littleEndianShort(content, offset + 28);
            int extraLength = littleEndianShort(content, offset + 30);
            int commentLength = littleEndianShort(content, offset + 32);
            int end = offset + CENTRAL_DIRECTORY_HEADER_BYTES
                    + nameLength + extraLength + commentLength;
            if (end > content.length) {
                throw new GradleException("Public snapshot ZIP has an invalid central directory");
            }
            String name = new String(
                    content,
                    offset + CENTRAL_DIRECTORY_HEADER_BYTES,
                    nameLength,
                    StandardCharsets.UTF_8
            );
            int externalAttributes = littleEndianInt(content, offset + 38);
            modes.put(name, externalAttributes >>> UNIX_MODE_SHIFT);
            offset = end;
        }
        return modes;
    }

    private static int findEndOfCentralDirectory(byte[] content) {
        // The fixed record may be followed by a ZIP comment of at most 65,535 bytes.
        int minimumOffset = Math.max(
                0,
                content.length - END_OF_CENTRAL_DIRECTORY_BYTES - ZIP16_MAX_VALUE
        );
        for (int offset = content.length - END_OF_CENTRAL_DIRECTORY_BYTES;
             offset >= minimumOffset;
             offset--) {
            if (littleEndianInt(content, offset) != END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                continue;
            }
            int commentLength = littleEndianShort(content, offset + 20);
            if (offset + END_OF_CENTRAL_DIRECTORY_BYTES + commentLength == content.length) {
                return offset;
            }
        }
        throw new GradleException("Public snapshot ZIP end-of-central-directory record is missing");
    }

    private static int littleEndianShort(byte[] content, int offset) {
        return Byte.toUnsignedInt(content[offset])
                | Byte.toUnsignedInt(content[offset + 1]) << 8;
    }

    private static int littleEndianInt(byte[] content, int offset) {
        return littleEndianShort(content, offset)
                | littleEndianShort(content, offset + 2) << 16;
    }
}
