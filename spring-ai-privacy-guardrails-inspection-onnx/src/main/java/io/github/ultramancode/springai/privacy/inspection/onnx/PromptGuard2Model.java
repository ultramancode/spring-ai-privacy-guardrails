package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.nio.file.Path;
import java.util.List;

/** Prompt Guard 2 binary classifier (BENIGN=0, MALICIOUS=1), with 512-token windows. Supply the matching tokenizer.json. */
public final class PromptGuard2Model extends HuggingFaceSequenceClassifier {
    public PromptGuard2Model(Path tokenizer) {
        this(tokenizer, 0.5, 64);
    }

    public PromptGuard2Model(Path tokenizer, double threshold, int overlapTokens) {
        super("prompt-guard", tokenizer, 512, overlapTokens, 2, Activation.SOFTMAX,
                List.of(new Label(1, InspectionFinding.Category.PROMPT_ATTACK, "MALICIOUS")), threshold);
    }
}
