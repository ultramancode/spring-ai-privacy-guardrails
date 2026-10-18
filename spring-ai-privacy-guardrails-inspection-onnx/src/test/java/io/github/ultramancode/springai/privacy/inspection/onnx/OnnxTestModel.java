package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.nio.file.Path;
import java.util.List;

/**
 * Test defaults for Xenova/toxic-bert, pinned in scripts/inspection/test-model.properties.
 * Its config.json maps indices 0 through 5 to six classes, with insult at index 4.
 * The ONNX model exposes those logits in the output named "logits".
 */
final class OnnxTestModel {

    static final String SAFE = "Thank you for your help. ";
    static final String INSULT = "You are an idiot. ";
    static final String LONG_TEXT_WITH_INSULT_AT_END = SAFE.repeat(40) + INSULT.repeat(20);

    private OnnxTestModel() {}

    static Path directory() {
        return Path.of(System.getProperty("inspection.test.model"));
    }

    static Path model() {
        return directory().resolve("model.onnx");
    }

    static OnnxClassificationConfig.Builder classificationBuilder(Path tokenizerPath, Path tokenizerConfigPath) {
        return OnnxClassificationConfig.builder()
                .tokenizer(tokenizerPath)
                .tokenizerConfig(tokenizerConfigPath)
                .windowSize(128)
                .overlapSize(32)
                .logitCount(6)
                .activation(OnnxClassificationConfig.Activation.SIGMOID)
                .labels(List.of(new OnnxClassificationConfig.Label(4,
                        InspectionFinding.Category.TOXICITY, "INSULT", 0.8)));
    }
}
