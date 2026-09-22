package io.github.ultramancode.springai.privacy.inspection.onnx;

import java.nio.file.Path;
import java.util.Objects;

/** Local ONNX graph and CPU execution settings; tokenizer and scoring belong to the adapter. */
public record OnnxInspectionConfig(Path model, int intraOpThreads) {
    public OnnxInspectionConfig {
        Objects.requireNonNull(model, "model");
        if (intraOpThreads < 1 || intraOpThreads > 64) {
            throw new IllegalArgumentException("intraOpThreads must be between 1 and 64");
        }
    }

    public static OnnxInspectionConfig defaults(Path model) {
        return new OnnxInspectionConfig(model, 2);
    }

    @Override
    public String toString() {
        return "OnnxInspectionConfig[model=<local>, intraOpThreads=" + intraOpThreads + "]";
    }
}
