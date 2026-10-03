package io.github.ultramancode.springai.privacy.inspection.onnx;

import java.nio.file.Path;
import java.util.Objects;

/** Local graph, CPU threads and maximum inference windows across one request. */
public record OnnxInspectionConfig(Path model, int intraOpThreads, int maxWindows) {

    public OnnxInspectionConfig {
        Objects.requireNonNull(model, "model");
        if (intraOpThreads < 1) {
            throw new IllegalArgumentException("intraOpThreads must be positive");
        }
        if (maxWindows < 1) {
            throw new IllegalArgumentException("maxWindows must be positive");
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
