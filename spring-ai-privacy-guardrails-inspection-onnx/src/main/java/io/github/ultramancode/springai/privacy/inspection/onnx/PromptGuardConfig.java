package io.github.ultramancode.springai.privacy.inspection.onnx;

import java.nio.file.Path;
import java.util.Objects;

/** Local, explicitly provisioned Prompt Guard 2 export and its matching tokenizer.json. */
public record PromptGuardConfig(
        Path model, Path tokenizer, double threshold, int overlapTokens, int intraOpThreads) {

    public PromptGuardConfig {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(tokenizer, "tokenizer");
        if (!Double.isFinite(threshold) || threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException(
                    "threshold must be finite, greater than 0 and at most 1");
        }
        if (overlapTokens < 0 || overlapTokens > 256) {
            throw new IllegalArgumentException("overlapTokens must be between 0 and 256");
        }
        if (intraOpThreads < 1 || intraOpThreads > 64) {
            throw new IllegalArgumentException("intraOpThreads must be between 1 and 64");
        }
    }

    public static PromptGuardConfig defaults(Path model, Path tokenizer) {
        return new PromptGuardConfig(model, tokenizer, 0.5, 64, 2);
    }

    @Override
    public String toString() {
        return "PromptGuardConfig[artifacts=<local>, threshold=" + threshold + "]";
    }
}
