package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.nio.file.Path;
import java.util.List;

final class PublicModel {

    static final String SAFE = "Thank you for your help. ";
    static final String INSULT = "Ты идиот. ";
    static final String LONG_TAIL = SAFE.repeat(40) + INSULT.repeat(20);

    private PublicModel() {}

    static Path directory() {
        return Path.of(System.getProperty("inspection.test.model"));
    }

    static Path model() {
        return directory().resolve("model.onnx");
    }

    static OnnxClassificationConfig.Builder classification(Path tokenizer, Path settings) {
        return OnnxClassificationConfig.builder()
                .tokenizer(tokenizer)
                .tokenizerConfig(settings)
                .maxTokens(128)
                .overlapTokens(32)
                .logitCount(5)
                .activation(OnnxClassificationConfig.Activation.SIGMOID)
                .labels(List.of(new OnnxClassificationConfig.Label(1,
                        InspectionFinding.Category.TOXICITY, "INSULT", 0.8)));
    }
}
