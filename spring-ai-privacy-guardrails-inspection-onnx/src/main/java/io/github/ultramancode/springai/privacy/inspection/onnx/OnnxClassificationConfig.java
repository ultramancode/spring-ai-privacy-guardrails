package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit semantics for a Hugging Face sequence classifier with FLOAT logits shaped [1, classCount].
 * Supply matching local fast-tokenizer exports. The tokenizer.json graph must contain normalization,
 * pre-tokenization, added tokens and special-token processing. Only padding and truncation settings
 * are read from tokenizer_config.json. Python tokenizer-class initialization is not executed.
 * No label meaning or activation is inferred from a repository name.
 * Multiple labels may map the same output index. Their thresholds apply independently.
 * Inspector initialization requires a positive content window after subtracting the graph's
 * single-sequence special tokens. Overlap must be strictly smaller than that content window.
 *
 * @param maxTokens window length including special tokens and padding
 * @param overlapTokens number of content tokens repeated between adjacent windows
 * @param classCount number of logits, including any benign classes without a finding mapping
 * @param tokenInputType integer type shared by the model's token inputs, normally INT64
 */
public record OnnxClassificationConfig(
        Path tokenizer, Path tokenizerConfig, int maxTokens, int overlapTokens,
        int classCount, Activation activation, List<Label> labels, TokenInputType tokenInputType) {

    public enum TokenInputType {
        INT64, INT32
    }

    /** Uses INT64 token inputs, as in standard Hugging Face ONNX exports. */
    public OnnxClassificationConfig(Path tokenizer, Path tokenizerConfig, int maxTokens, int overlapTokens,
            int classCount, Activation activation, List<Label> labels) {
        this(tokenizer, tokenizerConfig, maxTokens, overlapTokens, classCount, activation, labels, TokenInputType.INT64);
    }

    public enum Activation {
        /** Mutually exclusive classes. Requires at least two logits. */
        SOFTMAX,
        /** Independent labels, including a single-logit binary classifier. */
        SIGMOID
    }

    /** A finding mapping for one output index. Codes are unique, payload-free diagnostic identifiers. */
    public record Label(int index, InspectionFinding.Category category, String code, double threshold) {
        public Label {
            if (index < 0) {
                throw new IllegalArgumentException("Label index must be nonnegative");
            }
            Objects.requireNonNull(category, "category");
            ContentSegment.requireIdentifier(code);
            if (!Double.isFinite(threshold) || threshold <= 0 || threshold > 1) {
                throw new IllegalArgumentException("threshold must be finite, greater than zero and at most one");
            }
        }
    }

    public OnnxClassificationConfig {
        Objects.requireNonNull(tokenizer, "tokenizer");
        Objects.requireNonNull(tokenizerConfig, "tokenizerConfig");
        Objects.requireNonNull(activation, "activation");
        Objects.requireNonNull(tokenInputType, "tokenInputType");
        if (maxTokens < 1 || overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("Invalid token window or overlap");
        }
        if (classCount < 1
                || (activation == Activation.SOFTMAX && classCount < 2)) {
            throw new IllegalArgumentException("Invalid classifier output size");
        }
        labels = List.copyOf(labels);
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("Configure at least one finding label");
        }
        Set<String> codes = new HashSet<>();
        for (Label label : labels) {
            if (label.index() >= classCount || !codes.add(label.code())) {
                throw new IllegalArgumentException("Finding labels need valid output indices and distinct codes");
            }
        }
    }

    /** Prompt Guard 2 22M/86M: BENIGN=0, MALICIOUS=1, 512 tokens, 64-token overlap. */
    public static OnnxClassificationConfig promptGuard2(
            Path tokenizer, Path tokenizerConfig, double threshold) {
        return new OnnxClassificationConfig(tokenizer, tokenizerConfig, 512, 64, 2, Activation.SOFTMAX,
                List.of(new Label(1, InspectionFinding.Category.PROMPT_ATTACK, "MALICIOUS", threshold)));
    }

    @Override
    public String toString() {
        return "OnnxClassificationConfig[tokenizer=<local>, tokenizerConfig=<local>, maxTokens=" + maxTokens
                + ", overlapTokens=" + overlapTokens + ", classCount=" + classCount
                + ", activation=" + activation + ", labels=" + labels + ", tokenInputType=" + tokenInputType + "]";
    }
}
