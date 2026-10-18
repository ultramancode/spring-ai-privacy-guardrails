package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static io.github.ultramancode.springai.privacy.inspection.onnx.OnnxClassificationConfig.Activation.SIGMOID;
import static io.github.ultramancode.springai.privacy.inspection.onnx.OnnxClassificationConfig.Activation.SOFTMAX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OnnxClassificationConfigTest {

    private final Path tokenizerPath = Path.of("tokenizer.json");
    private final Path tokenizerConfigPath = Path.of("tokenizer_config.json");

    private OnnxClassificationConfig.Label label(int index, String code, double threshold) {
        return new OnnxClassificationConfig.Label(index, InspectionFinding.Category.PROMPT_ATTACK, code, threshold);
    }

    private OnnxClassificationConfig.Builder builder() {
        return OnnxClassificationConfig.builder()
                .tokenizer(tokenizerPath)
                .tokenizerConfig(tokenizerConfigPath)
                .windowSize(256)
                .logitCount(3)
                .activation(SOFTMAX)
                .labels(List.of(label(1, "ATTACK", 0.9)));
    }

    @Test
    void labelsAreCopiedWhenTheConfigIsBuilt() {
        List<OnnxClassificationConfig.Label> labels = new ArrayList<>(List.of(label(1, "ATTACK", 0.9)));
        OnnxClassificationConfig classification = builder().labels(labels).build();

        labels.clear();

        assertThat(classification.labels()).containsExactly(label(1, "ATTACK", 0.9));
        assertThatThrownBy(() -> classification.labels().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void builderDefaultsOverlapInputTypeAndOutputName() {
        OnnxClassificationConfig classification = builder().build();
        assertThat(classification.overlapSize()).isZero();
        assertThat(classification.tokenInputType()).isEqualTo(OnnxClassificationConfig.TokenInputType.INT64);
        assertThat(classification.modelOutputName()).isEqualTo("logits");
    }

    @Test
    void blankOutputNamesAreRejected() {
        assertThatThrownBy(() -> builder().modelOutputName(" ").build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "-1, 0", "8, -1", "8, 8"})
    void invalidTokenWindowsAreRejected(int windowSize, int overlapSize) {
        assertThatThrownBy(() -> builder().windowSize(windowSize).overlapSize(overlapSize).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void labelIndexesCodesAndOutputCountMustAgree() {
        assertThatThrownBy(() -> builder().logitCount(2).labels(List.of(label(2, "ATTACK", 0.5))).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().labels(List.of(label(0, "A", 0.5), label(1, "A", 0.5))).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().labels(List.of()).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().logitCount(1).labels(List.of(label(0, "A", 0.5))).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder().logitCount(0).activation(SIGMOID).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("logitCount must be greater than 0");
    }

    @Test
    void executionLimitsMustBePositive() {
        assertThatThrownBy(() -> new OnnxInspectionConfig(Path.of("model.onnx"), 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OnnxInspectionConfig(Path.of("model.onnx"), 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, -0.1, 0, 1.1})
    void invalidThresholdsAreRejected(double threshold) {
        assertThatThrownBy(() -> label(1, "ATTACK", threshold)).isInstanceOf(IllegalArgumentException.class);
    }

}
