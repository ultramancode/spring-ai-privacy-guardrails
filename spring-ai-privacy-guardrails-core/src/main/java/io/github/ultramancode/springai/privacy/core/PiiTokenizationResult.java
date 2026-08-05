package io.github.ultramancode.springai.privacy.core;

import java.util.Objects;

/**
 * Tokenized text and the single resolved analysis that produced it.
 *
 * @param tokenizedText transformed text containing request-scoped opaque tokens
 * @param analysis resolved analysis used for the transformation
 */
public record PiiTokenizationResult(
        String tokenizedText,
        PiiAnalysisResult analysis
) {

    /** Creates a result linked to its non-null analysis. */
    public PiiTokenizationResult {
        analysis = Objects.requireNonNull(analysis, "analysis must not be null");
    }

    @Override
    public String toString() {
        return "PiiTokenizationResult[analysis=" + this.analysis + "]";
    }
}
