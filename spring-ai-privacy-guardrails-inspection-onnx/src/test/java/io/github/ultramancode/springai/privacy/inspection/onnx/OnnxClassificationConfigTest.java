package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static io.github.ultramancode.springai.privacy.inspection.onnx.OnnxClassificationConfig.Activation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxClassificationConfigTest {

    private final Path tokenizer = Path.of("tokenizer.json");
    private final Path config = Path.of("tokenizer_config.json");

    private OnnxClassificationConfig.Label label(int index, String code, double threshold) {
        return new OnnxClassificationConfig.Label(index, InspectionFinding.Category.PROMPT_ATTACK, code, threshold);
    }

    private OnnxClassificationConfig.Builder builder() {
        return OnnxClassificationConfig.builder()
                .tokenizer(tokenizer)
                .tokenizerConfig(config)
                .maxTokens(256)
                .logitCount(3)
                .activation(SOFTMAX)
                .labels(List.of(label(1, "ATTACK", 0.9)));
    }

    @Test
    void builtSettingsKeepTheirValuesWhenLabelsOrTheBuilderChange() {
        List<OnnxClassificationConfig.Label> labels = new ArrayList<>(List.of(label(1, "ATTACK", 0.9)));
        OnnxClassificationConfig.Builder builder = builder()
                .overlapTokens(32)
                .modelOutputName("classification_output")
                .tokenInputType(OnnxClassificationConfig.TokenInputType.INT32)
                .labels(labels);
        OnnxClassificationConfig classification = builder.build();
        assertThat(classification.tokenizer()).isEqualTo(tokenizer);
        assertThat(classification.tokenizerConfig()).isEqualTo(config);
        assertThat(classification.maxTokens()).isEqualTo(256);
        assertThat(classification.overlapTokens()).isEqualTo(32);
        assertThat(classification.logitCount()).isEqualTo(3);
        assertThat(classification.activation()).isEqualTo(SOFTMAX);
        assertThat(classification.tokenInputType()).isEqualTo(OnnxClassificationConfig.TokenInputType.INT32);
        assertThat(classification.modelOutputName()).isEqualTo("classification_output");
        labels.clear();
        OnnxClassificationConfig next = builder.maxTokens(128).modelOutputName("scores")
                .labels(List.of(label(2, "LEAKING", 0.8))).build();
        assertThat(next.maxTokens()).isEqualTo(128);
        assertThat(next.modelOutputName()).isEqualTo("scores");
        assertThat(classification.modelOutputName()).isEqualTo("classification_output");
        assertThat(next.labels()).containsExactly(label(2, "LEAKING", 0.8));
        assertThat(classification.maxTokens()).isEqualTo(256);
        assertThat(classification.labels()).containsExactly(label(1, "ATTACK", 0.9));
        assertThatThrownBy(() -> classification.labels().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builderDefaultsOverlapInputTypeAndOutputName() {
        OnnxClassificationConfig classification = builder()
                .logitCount(1)
                .activation(SIGMOID)
                .labels(List.of(label(0, "ATTACK", 0.9)))
                .build();
        assertThat(classification.overlapTokens()).isZero();
        assertThat(classification.tokenInputType()).isEqualTo(OnnxClassificationConfig.TokenInputType.INT64);
        assertThat(classification.modelOutputName()).isEqualTo("logits");
        assertThat(classification.logitCount()).isEqualTo(1);
        assertThat(classification.activation()).isEqualTo(SIGMOID);
        assertThat(classification.labels()).containsExactly(label(0, "ATTACK", 0.9));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tokenizer", "tokenizerConfig", "maxTokens", "logitCount", "activation", "labels"})
    void builderRequiresModelContractAndFindingMappings(String missing) {
        OnnxClassificationConfig.Builder builder = OnnxClassificationConfig.builder();
        if (!missing.equals("tokenizer")) {
            builder.tokenizer(tokenizer);
        }
        if (!missing.equals("tokenizerConfig")) {
            builder.tokenizerConfig(config);
        }
        if (!missing.equals("maxTokens")) {
            builder.maxTokens(256);
        }
        if (!missing.equals("logitCount")) {
            builder.logitCount(3);
        }
        if (!missing.equals("activation")) {
            builder.activation(SOFTMAX);
        }
        if (!missing.equals("labels")) {
            builder.labels(List.of(label(1, "ATTACK", 0.9)));
        }
        assertThatThrownBy(builder::build).isInstanceOf(NullPointerException.class).hasMessage(missing);
    }

    @Test
    void explicitlyNullInputTypeDoesNotFallBackToTheDefault() {
        assertThatThrownBy(() -> builder().tokenInputType(null).build())
                .isInstanceOf(NullPointerException.class).hasMessage("tokenInputType");
    }

    @Test
    void explicitlyNullOutputNameDoesNotFallBackToTheDefault() {
        assertThatThrownBy(() -> builder().modelOutputName(null).build())
                .isInstanceOf(NullPointerException.class).hasMessage("modelOutputName");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void outputNamesMustNotBeBlank(String name) {
        assertThatThrownBy(() -> builder().modelOutputName(name).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "-1, 0", "8, -1", "8, 8"})
    void invalidTokenWindowsAreRejected(int maxTokens, int overlapTokens) {
        assertThatThrownBy(() -> builder().maxTokens(maxTokens).overlapTokens(overlapTokens).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void labelIndicesCodesAndOutputCountMustAgree() {
        assertThatThrownBy(() -> builder().logitCount(2).labels(List.of(label(2, "ATTACK", 0.5))).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().labels(List.of(label(0, "A", 0.5), label(1, "A", 0.5))).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().labels(List.of()).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().logitCount(1).labels(List.of(label(0, "A", 0.5))).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().logitCount(0).activation(SIGMOID).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void largeConfigurationsRespectWindowAndExecutionConstraints() {
        List<OnnxClassificationConfig.Label> labels = IntStream.range(0, 300)
                .mapToObj(i -> label(i, "label" + i, 0.5)).toList();
        OnnxClassificationConfig large = builder().maxTokens(40_000).overlapTokens(39_999)
                .logitCount(5000).activation(SIGMOID).labels(labels).build();
        assertThat(large.labels()).hasSize(300);
        assertThat(new OnnxInspectionConfig(Path.of("model.onnx"), 65, 10_001).maxWindows()).isEqualTo(10_001);
        assertThat(builder().maxTokens(1).logitCount(1).activation(SIGMOID)
                .labels(List.of(label(0, "one", 0.5))).build().maxTokens()).isEqualTo(1);
        assertThatThrownBy(() -> new OnnxInspectionConfig(Path.of("model.onnx"), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OnnxInspectionConfig(Path.of("model.onnx"), 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, -0.1, 0, 1.1})
    void thresholdsCannotSilentlyAcceptInvalidValues(double threshold) {
        assertThatThrownBy(() -> label(1, "ATTACK", threshold)).isInstanceOf(IllegalArgumentException.class);
    }

}
