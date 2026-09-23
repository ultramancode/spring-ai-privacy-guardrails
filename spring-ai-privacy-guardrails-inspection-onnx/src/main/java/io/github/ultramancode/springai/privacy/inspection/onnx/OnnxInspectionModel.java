package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.util.List;
import java.util.Map;

/**
 * Model-specific tokenization and output interpretation for batch-one text classifiers.
 * The runtime supports rank-two INT32/INT64 token inputs; adapters choose names, window
 * lengths and labels. This is not an adapter for arbitrary ONNX graphs or generative models.
 * An inspector owns its adapter and serializes validation, encoding, decoding and close.
 */
public interface OnnxInspectionModel extends AutoCloseable {
    /** Default diagnostic ID for this model adapter; an inspector may use a distinct instance ID. */
    String modelId();

    /** Checks the provisioned graph before the first request; reject incompatible exports. */
    void validate(Map<String, NodeInfo> inputs, Map<String, NodeInfo> outputs);

    /**
     * Returns every overlapping window, with special tokens and all required input names.
     * Never silently truncate. Fail with LIMIT_EXCEEDED if complete coverage exceeds maxWindows.
     * Arrays remain valid until the current inspection finishes; no native tensors escape here.
     */
    List<Map<String, long[]>> encode(String text, int maxWindows);

    /** Reads one window's outputs while they are open; retain no native output references. */
    List<InspectionFinding> decode(String segmentId, OrtSession.Result outputs) throws OrtException;

    /** Releases model-owned tokenizer resources, not the runtime session or global environment. */
    @Override
    void close();
}
