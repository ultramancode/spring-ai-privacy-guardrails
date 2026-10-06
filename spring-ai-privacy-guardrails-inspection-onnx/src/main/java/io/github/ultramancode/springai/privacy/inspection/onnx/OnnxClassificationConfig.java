package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit semantics for a sequence classifier with FLOAT logits shaped [1, logitCount].
 * Supply matching local tokenizer files in the Tokenizers JSON format supported by DJL.
 * Model files may come from any source. The tokenizer.json graph must contain normalization,
 * pre-tokenization, added tokens and special-token processing. Only padding and truncation settings
 * are read from tokenizer_config.json. Python tokenizer-class initialization is not executed.
 * No label meaning or activation is inferred from a repository name.
 * Logit count, activation, label indices and token input type must match the model export.
 * Applications choose finding categories, diagnostic codes and thresholds for those indices.
 * Window length must be supported by the export. Overlap is an application scanning choice.
 * Model config.json is not read. The caller supplies the model contract explicitly.
 * Multiple labels may map the same output index. Their thresholds apply independently.
 * Inspector initialization requires a positive content window after subtracting the graph's
 * single-sequence special tokens. Overlap must be strictly smaller than that content window.
 *
 * @param tokenizer matching Tokenizers JSON graph supported by DJL
 * @param tokenizerConfig matching padding and truncation settings
 * @param maxTokens supported length of each inference window, including special tokens and padding
 * @param overlapTokens number of content tokens repeated between adjacent windows
 * @param logitCount expected number of raw scores in the model's [1, logitCount] logits output
 * @param activation score calculation required by the model's classification contract
 * @param labels application finding mappings for selected output indices, not the complete model label list
 * @param tokenInputType integer type shared by the model's token inputs, normally INT64
 * @param modelOutputName name of the raw logits output in the model export
 */
public record OnnxClassificationConfig(
        Path tokenizer, Path tokenizerConfig, int maxTokens, int overlapTokens,
        int logitCount, Activation activation, List<Label> labels, TokenInputType tokenInputType,
        String modelOutputName) {

    public enum TokenInputType {
        INT64, INT32
    }

    /** Built-in conversions of raw logits to scores. Select the conversion required by the model. */
    public enum Activation {
        /** SOFTMAX for a model trained with mutually exclusive class logits. Requires at least two logits. */
        SOFTMAX,
        /** Independent labels, including a single-logit binary classifier. */
        SIGMOID
    }

    /**
     * Maps a verified model output index to an application finding.
     * The index follows the model's logits output order. Category, code and threshold are application choices.
     * A finding is emitted when the score after activation is greater than or equal to the threshold.
     * Codes are unique, payload-free diagnostic identifiers.
     */
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
        Objects.requireNonNull(modelOutputName, "modelOutputName");
        if (modelOutputName.isBlank()) {
            throw new IllegalArgumentException("modelOutputName must not be blank");
        }
        if (maxTokens < 1 || overlapTokens < 0 || overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("Invalid token window or overlap");
        }
        if (logitCount < 1
                || (activation == Activation.SOFTMAX && logitCount < 2)) {
            throw new IllegalArgumentException("Invalid classifier output size");
        }
        labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("Configure at least one finding label");
        }
        Set<String> codes = new HashSet<>();
        for (Label label : labels) {
            if (label.index() >= logitCount || !codes.add(label.code())) {
                throw new IllegalArgumentException("Finding labels need valid output indices and distinct codes");
            }
        }
    }

    /**
     * Creates a builder with no overlap, INT64 token inputs and a logits output name.
     * Tokenizer files, window length, logit count, activation and labels are required.
     * Model contract values are supplied by the caller.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds immutable classification settings with explicit model semantics. */
    public static final class Builder {

        private Path tokenizer;
        private Path tokenizerConfig;
        private Integer maxTokens;
        private int overlapTokens;
        private Integer logitCount;
        private Activation activation;
        private List<Label> labels;
        private TokenInputType tokenInputType = TokenInputType.INT64;
        private String modelOutputName = "logits";

        private Builder() {
        }

        /** Sets the matching local tokenizer graph in the Tokenizers JSON format supported by DJL. */
        public Builder tokenizer(Path tokenizer) {
            this.tokenizer = tokenizer;
            return this;
        }

        /** Sets the matching local padding and truncation settings. */
        public Builder tokenizerConfig(Path tokenizerConfig) {
            this.tokenizerConfig = tokenizerConfig;
            return this;
        }

        /** Sets each inference window's length, including special tokens and padding. Longer text uses more windows. */
        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /** Sets content tokens repeated between windows. Defaults to zero. */
        public Builder overlapTokens(int overlapTokens) {
            this.overlapTokens = overlapTokens;
            return this;
        }

        /** Sets the expected number of raw scores. It validates output width without changing the model. */
        public Builder logitCount(int logitCount) {
            this.logitCount = logitCount;
            return this;
        }

        /** Sets the score calculation required by the model's classification contract. */
        public Builder activation(Activation activation) {
            this.activation = activation;
            return this;
        }

        /** Sets selected output mappings. Unmapped outputs still contribute to SOFTMAX scores. */
        public Builder labels(List<Label> labels) {
            this.labels = labels;
            return this;
        }

        /** Sets the integer type shared by all token inputs. Defaults to INT64. */
        public Builder tokenInputType(TokenInputType tokenInputType) {
            this.tokenInputType = tokenInputType;
            return this;
        }

        /** Sets the exported raw logits output name. Defaults to logits. */
        public Builder modelOutputName(String modelOutputName) {
            this.modelOutputName = modelOutputName;
            return this;
        }

        /**
         * Validates the configured values and creates immutable classification settings.
         *
         * @return validated classification settings
         * @throws NullPointerException if a required setting is missing or null
         * @throws IllegalArgumentException if a window or label configuration is invalid
         */
        public OnnxClassificationConfig build() {
            return new OnnxClassificationConfig(tokenizer, tokenizerConfig,
                    Objects.requireNonNull(maxTokens, "maxTokens"), overlapTokens,
                    Objects.requireNonNull(logitCount, "logitCount"), activation, labels, tokenInputType,
                    modelOutputName);
        }
    }

    @Override
    public String toString() {
        return "OnnxClassificationConfig[tokenizer=<local>, tokenizerConfig=<local>, maxTokens=" + maxTokens
                + ", overlapTokens=" + overlapTokens + ", logitCount=" + logitCount
                + ", activation=" + activation + ", labels=" + labels + ", tokenInputType=" + tokenInputType + "]";
    }
}
