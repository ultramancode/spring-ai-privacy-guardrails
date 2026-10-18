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

    private final OrtEnvironment ortEnvironment;
    private final OrtSession ortSession;
    private final Set<String> inputNames;
    private final OnnxClassificationConfig onnxClassificationConfig;

    OnnxSequenceClassifier(
            OnnxInspectionConfig onnxInspectionConfig, OnnxClassificationConfig onnxClassificationConfig) {
        this.onnxClassificationConfig = onnxClassificationConfig;
        OrtSession loadedOrtSession = null;
        try {
            if (!Files.isRegularFile(onnxInspectionConfig.model())) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            ortEnvironment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setIntraOpNumThreads(onnxInspectionConfig.intraOpThreads());
                loadedOrtSession = ortEnvironment.createSession(onnxInspectionConfig.model().toString(), options);
            }
            validateInputs(loadedOrtSession.getInputInfo(), onnxClassificationConfig);
            NodeInfo outputInfo = loadedOrtSession.getOutputInfo().get(onnxClassificationConfig.modelOutputName());
            if (outputInfo == null || !(outputInfo.getInfo() instanceof TensorInfo outputTensorInfo)) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            if (outputTensorInfo.type != OnnxJavaType.FLOAT
                    || !matchesShape(outputTensorInfo, onnxClassificationConfig.logitCount())) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            inputNames = Set.copyOf(loadedOrtSession.getInputNames());
            ortSession = loadedOrtSession;
        } catch (OrtException | RuntimeException | LinkageError ex) {
            if (loadedOrtSession != null) {
                try {
                    loadedOrtSession.close();
                } catch (OrtException ignored) {
                    // Keep the sanitized configuration failure if cleanup also fails.
                }
            }
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
    }

    private static void validateInputs(
            Map<String, NodeInfo> inputs, OnnxClassificationConfig onnxClassificationConfig) {
        OnnxJavaType expectedInputType;
        if (onnxClassificationConfig.tokenInputType() == OnnxClassificationConfig.TokenInputType.INT32) {
            expectedInputType = OnnxJavaType.INT32;
        } else {
            expectedInputType = OnnxJavaType.INT64;
        }
        if (!inputs.containsKey("input_ids")) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
        Set<String> supportedInputNames = Set.of("input_ids", "attention_mask", "token_type_ids");
        for (Map.Entry<String, NodeInfo> input : inputs.entrySet()) {
            String inputName = input.getKey();
            NodeInfo inputInfo = input.getValue();
            if (!supportedInputNames.contains(inputName)) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            if (!(inputInfo.getInfo() instanceof TensorInfo inputTensorInfo)) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            if (inputTensorInfo.type != expectedInputType
                    || !matchesShape(inputTensorInfo, onnxClassificationConfig.windowSize())) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
        }
    }

    /**
     * Checks for [1, expectedSecondDimensionSize], treating negative dimensions as dynamic.
     * The second dimension is the window size for inputs and the logit count for outputs.
     */
    private static boolean matchesShape(TensorInfo tensorInfo, int expectedSecondDimensionSize) {
        long[] shape = tensorInfo.getShape();
        if (shape.length != 2) {
            return false;
        }
        long batchSize = shape[0];
        if (batchSize >= 0 && batchSize != 1) {
            return false;
        }
        long secondDimensionSize = shape[1];
        if (secondDimensionSize < 0) {
            return true;
        }
        return secondDimensionSize == expectedSecondDimensionSize;
    }

    double[] classify(Encoding encoding) {
        Map<String, OnnxTensor> inputTensors = new HashMap<>();
        try {
            for (String inputName : inputNames) {
                long[] inputValues = switch (inputName) {
                    case "input_ids" -> encoding.getIds();
                    case "attention_mask" -> encoding.getAttentionMask();
                    case "token_type_ids" -> encoding.getTypeIds();
                    default -> throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
                };
                inputTensors.put(inputName, createInputTensor(inputValues));
            }
            String outputName = onnxClassificationConfig.modelOutputName();
            try (OrtSession.Result result = ortSession.run(inputTensors, Set.of(outputName))) {
                OnnxValue output = result.get(outputName).orElseThrow();
                if (!(output instanceof OnnxTensor)) {
                    throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
                }
                OnnxTensor outputTensor = (OnnxTensor) output;
                TensorInfo outputTensorInfo = outputTensor.getInfo();
                if (outputTensorInfo.type != OnnxJavaType.FLOAT) {
                    throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
                }

                long[] outputShape = outputTensorInfo.getShape();
                long[] expectedOutputShape = {1, onnxClassificationConfig.logitCount()};
                if (!Arrays.equals(outputShape, expectedOutputShape)) {
                    throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
                }
                float[] logits = new float[onnxClassificationConfig.logitCount()];
                outputTensor.getFloatBuffer().get(logits);
                return calculateScores(logits);
            }
        } catch (OrtException | RuntimeException ex) {
            throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
        } finally {
            inputTensors.values().forEach(OnnxTensor::close);
        }
    }

    private OnnxTensor createInputTensor(long[] inputValues) throws OrtException {
        long[] shape = {1, inputValues.length};
        if (onnxClassificationConfig.tokenInputType() == OnnxClassificationConfig.TokenInputType.INT32) {
            int[] intValues = new int[inputValues.length];
            for (int i = 0; i < inputValues.length; i++) {
                intValues[i] = Math.toIntExact(inputValues[i]);
            }
            return OnnxTensor.createTensor(ortEnvironment, IntBuffer.wrap(intValues), shape);
        }
        return OnnxTensor.createTensor(ortEnvironment, LongBuffer.wrap(inputValues), shape);
    }

    private double[] calculateScores(float[] logits) {
        for (float logit : logits) {
            if (!Float.isFinite(logit)) {
                throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
            }
        }
        return switch (onnxClassificationConfig.activation()) {
            case SOFTMAX -> calculateSoftmaxScores(logits);
            case SIGMOID -> calculateSigmoidScores(logits);
        };
    }

    private static double[] calculateSoftmaxScores(float[] logits) {
        double maximumLogit = Double.NEGATIVE_INFINITY;
        for (float logit : logits) {
            maximumLogit = Math.max(maximumLogit, logit);
        }
        double[] scores = new double[logits.length];
        double exponentialSum = 0;
        // Subtracting the maximum logit prevents overflow without changing softmax scores.
        for (int i = 0; i < logits.length; i++) {
            scores[i] = Math.exp(logits[i] - maximumLogit);
            exponentialSum += scores[i];
        }
        for (int i = 0; i < scores.length; i++) {
            scores[i] /= exponentialSum;
        }
        return scores;
    }

    private static double[] calculateSigmoidScores(float[] logits) {
        double[] scores = new double[logits.length];
        // A non-positive exponent avoids overflow in both sigmoid branches.
        for (int i = 0; i < logits.length; i++) {
            double logit = logits[i];
            if (logit >= 0) {
                double exponential = Math.exp(-logit);
                scores[i] = 1 / (1 + exponential);
            } else {
                double exponential = Math.exp(logit);
                scores[i] = exponential / (1 + exponential);
            }
        }
        return scores;
    }

    @Override
    public void close() {
        try {
            ortSession.close();
        } catch (OrtException ex) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
    }
}
