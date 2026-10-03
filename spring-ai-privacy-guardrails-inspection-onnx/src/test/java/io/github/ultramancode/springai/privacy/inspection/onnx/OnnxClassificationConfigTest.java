package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
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

    @Test
    void labelIndicesCodesAndOutputCountMustAgree() {
        assertThatThrownBy(() -> new OnnxClassificationConfig(tokenizer, config, 512, 64, 2, SOFTMAX,
                List.of(label(2, "ATTACK", 0.5)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OnnxClassificationConfig(tokenizer, config, 512, 64, 2, SOFTMAX,
                List.of(label(0, "A", 0.5), label(1, "A", 0.5)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OnnxClassificationConfig(tokenizer, config, 512, 64, 2, SOFTMAX,
                List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OnnxClassificationConfig(tokenizer, config, 512, 64, 1, SOFTMAX,
                List.of(label(0, "A", 0.5)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void largeConfigurationsRespectWindowAndExecutionConstraints() {
        List<OnnxClassificationConfig.Label> labels = IntStream.range(0, 300)
                .mapToObj(i -> label(i, "label" + i, 0.5)).toList();
        OnnxClassificationConfig large = new OnnxClassificationConfig(
                tokenizer, config, 40_000, 39_999, 5000, SIGMOID, labels);
        assertThat(large.labels()).hasSize(300);
        assertThat(new OnnxInspectionConfig(Path.of("model.onnx"), 65, 10_001).maxWindows()).isEqualTo(10_001);
        assertThat(new OnnxClassificationConfig(tokenizer, config, 1, 0, 1, SIGMOID,
                List.of(label(0, "one", 0.5))).maxTokens()).isEqualTo(1);
        assertThatThrownBy(() -> new OnnxClassificationConfig(tokenizer, config, 8, 8, 1, SIGMOID,
                List.of(label(0, "one", 0.5)))).isInstanceOf(IllegalArgumentException.class);
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
