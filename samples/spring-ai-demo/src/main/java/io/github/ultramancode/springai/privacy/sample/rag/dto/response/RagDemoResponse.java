package io.github.ultramancode.springai.privacy.sample.rag.dto.response;

public record RagDemoResponse(
        String retrievedDocument,
        String modelInput,
        boolean retrievedDocumentContainsRawPii,
        boolean modelInputContainsRawPii,
        boolean modelInputContainsTokenizedPii,
        int activeSessionsAfterCall
) {
}
