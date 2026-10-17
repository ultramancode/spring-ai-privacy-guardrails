package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionIdentifiers;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Tokenization and finding mappings for an ONNX text classification model.
 * The selected model output must contain raw FLOAT logits with shape {@code [1, logitCount]}.
 *
 * <p>Use matching {@code tokenizer.json} and {@code tokenizer_config.json} files.
 * The padding token, padding type ID and padding/truncation directions in {@code tokenizer_config.json}
 * take precedence over those in {@code tokenizer.json}. Overlap must be smaller than
 * {@code maxTokens} minus the special-token count, which is checked when the tokenizer loads.
 *
 * @param tokenizer path to the model's {@code tokenizer.json} in Hugging Face Tokenizers format
 * @param tokenizerConfig path to the model's {@code tokenizer_config.json}
 * @param maxTokens supported length of each inference window, including special tokens and padding
 * @param overlapTokens number of content tokens repeated between adjacent windows
 * @param logitCount expected number of raw scores in the model's [1, logitCount] logits output
 * @param activation conversion from raw logits to scores, as specified by the model
 * @param labels finding mappings for selected output indexes, with distinct finding codes
 * @param tokenInputType integer type shared by the model's token inputs, normally INT64
 * @param modelOutputName name of the raw logits output in the ONNX model
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
     * Maps a zero-based model output index to a category and finding code.
     * A finding is emitted when the score after activation is greater than or equal to the threshold.
     * The threshold must be greater than 0 and less than or equal to 1.
     * Multiple labels can use the same output index.
     * Each label has its own threshold and must use a distinct finding code.
     */
    public record Label(int index, InspectionFinding.Category category, String code, double threshold) {
        public Label {
            if (index < 0) {
                throw new IllegalArgumentException("Label index must be 0 or greater");
            }
            Objects.requireNonNull(category, "category");
            InspectionIdentifiers.requireValid(code);
            if (!Double.isFinite(threshold) || threshold <= 0 || threshold > 1) {
                throw new IllegalArgumentException("threshold must be finite, greater than 0 and less than or equal to 1");
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
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be greater than 0");
        }
        if (overlapTokens < 0) {
            throw new IllegalArgumentException("overlapTokens must be 0 or greater");
        }
        if (overlapTokens >= maxTokens) {
            throw new IllegalArgumentException("overlapTokens must be less than maxTokens");
        }
        if (logitCount < 1) {
            throw new IllegalArgumentException("logitCount must be greater than 0");
        }
        if (activation == Activation.SOFTMAX && logitCount < 2) {
            throw new IllegalArgumentException("SOFTMAX requires at least 2 logits");
        }
        labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
        if (labels.isEmpty()) {
            throw new IllegalArgumentException("Configure at least one finding label");
        }
        Set<String> codes = new HashSet<>();
        for (Label label : labels) {
            if (label.index() >= logitCount) {
                throw new IllegalArgumentException("Label index must be less than logitCount");
            }
            if (!codes.add(label.code())) {
                throw new IllegalArgumentException("Label codes must be distinct");
            }
        }
    }

    /**
     * Creates a builder with no overlap, INT64 token inputs and the output name {@code logits}.
     * Tokenizer files, window length, logit count, activation and labels are required.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds immutable ONNX classification settings. */
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

        /** Sets the path to the model's local {@code tokenizer.json} file. */
        public Builder tokenizer(Path tokenizer) {
            this.tokenizer = tokenizer;
            return this;
        }

        /** Sets the path to the model's local {@code tokenizer_config.json} file. */
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

        /** Sets the number of raw scores in the model's output, including unmapped outputs. */
        public Builder logitCount(int logitCount) {
            this.logitCount = logitCount;
            return this;
        }

        /** Sets the model's conversion from raw logits to scores. */
        public Builder activation(Activation activation) {
            this.activation = activation;
            return this;
        }

        /**
         * Sets the finding mappings for selected output indexes.
         * SOFTMAX scores are calculated using all logits.
         */
        public Builder labels(List<Label> labels) {
            this.labels = labels;
            return this;
        }

        /** Sets the integer type shared by all token inputs. Defaults to INT64. */
        public Builder tokenInputType(TokenInputType tokenInputType) {
            this.tokenInputType = tokenInputType;
            return this;
        }

        /** Sets the raw logits output name in the ONNX model. Defaults to {@code logits}. */
        public Builder modelOutputName(String modelOutputName) {
            this.modelOutputName = modelOutputName;
            return this;
        }

        /**
         * Validates the configured values and creates immutable ONNX classification settings.
         *
         * @return validated ONNX classification settings
         * @throws NullPointerException if a required setting is missing or null
         * @throws IllegalArgumentException if a configured value is invalid
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
