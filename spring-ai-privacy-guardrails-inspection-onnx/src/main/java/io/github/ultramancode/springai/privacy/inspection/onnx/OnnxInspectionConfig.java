package io.github.ultramancode.springai.privacy.inspection.onnx;

import java.nio.file.Path;
import java.util.Objects;

/** Local graph, CPU settings and maximum windows per inspection; tokenization and scoring belong to the adapter. */
public record OnnxInspectionConfig(Path model, int intraOpThreads, int maxWindows) {
    public OnnxInspectionConfig {
        Objects.requireNonNull(model, "model");
        if (intraOpThreads < 1 || intraOpThreads > 64) {
            throw new IllegalArgumentException("intraOpThreads must be between 1 and 64");
        }
        if (maxWindows < 1 || maxWindows > 10_000) {
            throw new IllegalArgumentException("maxWindows must be between 1 and 10000");
        }
    }

    public static OnnxInspectionConfig defaults(Path model) {
        return new OnnxInspectionConfig(model, 2, 256);
    }

    @Override
    public String toString() {
        return "OnnxInspectionConfig[model=<local>, intraOpThreads=" + intraOpThreads
                + ", maxWindows=" + maxWindows + "]";
    }
}
