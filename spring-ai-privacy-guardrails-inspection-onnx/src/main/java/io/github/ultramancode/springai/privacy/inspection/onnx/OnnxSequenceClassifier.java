package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Classifies one token window with ONNX Runtime. The owning inspector serializes access. */
final class OnnxSequenceClassifier implements AutoCloseable {

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final Set<String> inputNames;
    private final OnnxClassificationConfig classification;

    OnnxSequenceClassifier(OnnxInspectionConfig config, OnnxClassificationConfig classification) {
        this.classification = classification;
        OrtSession loaded = null;
        try {
            if (!Files.isRegularFile(config.model())) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            environment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(config.intraOpThreads());
                options.setInterOpNumThreads(1);
                loaded = environment.createSession(config.model().toString(), options);
            }
            validateInputs(loaded.getInputInfo(), classification);
            NodeInfo output = loaded.getOutputInfo().get(classification.modelOutputName());
            if (output == null || !(output.getInfo() instanceof TensorInfo tensor)
                    || tensor.type != OnnxJavaType.FLOAT || !matchesShape(tensor, classification.logitCount())) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            inputNames = Set.copyOf(loaded.getInputNames());
            session = loaded;
        } catch (OrtException | RuntimeException | LinkageError ex) {
            if (loaded != null) {
                try {
                    loaded.close();
                } catch (OrtException ignored) {
                    // Keep the sanitized configuration failure if cleanup also fails.
                }
            }
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
    }

    private static void validateInputs(Map<String, NodeInfo> inputs, OnnxClassificationConfig config) {
        OnnxJavaType type = config.tokenInputType() == OnnxClassificationConfig.TokenInputType.INT32
                ? OnnxJavaType.INT32 : OnnxJavaType.INT64;
        if (!inputs.containsKey("input_ids")) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
        for (Map.Entry<String, NodeInfo> input : inputs.entrySet()) {
            if (!Set.of("input_ids", "attention_mask", "token_type_ids").contains(input.getKey())
                    || !(input.getValue().getInfo() instanceof TensorInfo tensor)
                    || tensor.type != type || !matchesShape(tensor, config.maxTokens())) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
        }
    }

    /** Dynamic dimensions are checked against the actual tensors during inference. */
    private static boolean matchesShape(TensorInfo tensor, int width) {
        long[] shape = tensor.getShape();
        return shape.length == 2 && (shape[0] < 0 || shape[0] == 1)
                && (shape[1] < 0 || shape[1] == width);
    }

    double[] classify(Encoding encoding) {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        try {
            for (String name : inputNames) {
                long[] values = switch (name) {
                    case "input_ids" -> encoding.getIds();
                    case "attention_mask" -> encoding.getAttentionMask();
                    case "token_type_ids" -> encoding.getTypeIds();
                    default -> throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
                };
                long[] shape = {1, values.length};
                OnnxTensor tensor;
                if (classification.tokenInputType() == OnnxClassificationConfig.TokenInputType.INT32) {
                    int[] integers = new int[values.length];
                    for (int i = 0; i < values.length; i++) {
                        integers[i] = Math.toIntExact(values[i]);
                    }
                    tensor = OnnxTensor.createTensor(environment, IntBuffer.wrap(integers), shape);
                } else {
                    tensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape);
                }
                inputs.put(name, tensor);
            }
            try (OrtSession.Result result = session.run(inputs, Set.of(classification.modelOutputName()))) {
                OnnxValue output = result.get(classification.modelOutputName()).orElseThrow();
                if (!(output instanceof OnnxTensor tensor) || tensor.getInfo().type != OnnxJavaType.FLOAT
                        || !Arrays.equals(tensor.getInfo().getShape(), new long[]{1, classification.logitCount()})) {
                    throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
                }
                float[] logits = new float[classification.logitCount()];
                tensor.getFloatBuffer().get(logits);
                return scores(logits);
            }
        } catch (OrtException | RuntimeException ex) {
            throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    private double[] scores(float[] logits) {
        double maximum = Double.NEGATIVE_INFINITY;
        for (float logit : logits) {
            if (!Float.isFinite(logit)) {
                throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
            }
            maximum = Math.max(maximum, logit);
        }
        double[] scores = new double[logits.length];
        if (classification.activation() == OnnxClassificationConfig.Activation.SOFTMAX) {
            double denominator = 0;
            for (int i = 0; i < logits.length; i++) {
                scores[i] = Math.exp(logits[i] - maximum);
                denominator += scores[i];
            }
            for (int i = 0; i < scores.length; i++) {
                scores[i] /= denominator;
            }
        } else {
            for (int i = 0; i < logits.length; i++) {
                double exponential = Math.exp(-Math.abs((double) logits[i]));
                scores[i] = logits[i] >= 0 ? 1 / (1 + exponential) : exponential / (1 + exponential);
            }
        }
        return scores;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException ex) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
    }
}
