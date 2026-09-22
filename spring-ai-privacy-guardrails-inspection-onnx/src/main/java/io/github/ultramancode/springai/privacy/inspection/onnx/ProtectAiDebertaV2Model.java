package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.nio.file.Path;
import java.util.List;

/** ProtectAI DeBERTa prompt-injection v2 (benign=0, injection=1), with 512-token windows. Supply the matching tokenizer.json. */
public final class ProtectAiDebertaV2Model extends HuggingFaceSequenceClassifier {
    public ProtectAiDebertaV2Model(Path tokenizer) {
        this(tokenizer, 0.5, 64);
    }

    public ProtectAiDebertaV2Model(Path tokenizer, double threshold, int overlapTokens) {
        super("protectai-deberta-v2", tokenizer, 512, overlapTokens, 2, Activation.SOFTMAX,
                List.of(new Label(1, InspectionFinding.Category.PROMPT_INJECTION, "INJECTION")), threshold);
    }
}
