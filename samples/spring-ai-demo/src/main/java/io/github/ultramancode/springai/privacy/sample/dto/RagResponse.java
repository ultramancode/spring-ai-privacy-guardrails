package io.github.ultramancode.springai.privacy.sample.dto;

public record RagResponse(
        String retrievedDocument,
        String modelVisibleContext,
        boolean retrievedDocumentContainsRawPii,
        boolean modelVisibleContextContainsRawPii,
        boolean modelVisibleContextContainsTokenizedPii,
        int activeSessionsAfterCall
) {
}
