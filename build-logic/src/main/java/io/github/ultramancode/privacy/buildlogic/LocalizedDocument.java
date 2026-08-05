package io.github.ultramancode.privacy.buildlogic;

public record LocalizedDocument(String sourcePath, String targetPath) {

    public LocalizedDocument {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new IllegalArgumentException("Localization source path must not be blank");
        }
        if (targetPath == null || targetPath.isBlank()) {
            throw new IllegalArgumentException("Localization target path must not be blank");
        }
    }
}
