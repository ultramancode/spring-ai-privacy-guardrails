package io.github.ultramancode.springai.privacy.inspection.onnx;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Local model and execution limits for ONNX inspection.
 *
 * @param model path to the ONNX model file
 * @param intraOpThreads positive number of CPU threads used within a model operation
 * @param maxWindows positive maximum number of inference windows across all request segments
 */
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

    /** Creates settings with two CPU threads and at most 256 inference windows per request. */
    public static OnnxInspectionConfig defaults(Path model) {
        return new OnnxInspectionConfig(model, 2, 256);
    }

    @Override
    public String toString() {
        return "OnnxInspectionConfig[model=<local>, intraOpThreads=" + intraOpThreads
                + ", maxWindows=" + maxWindows + "]";
    }
}
