package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.Device;
import ai.djl.MalformedModelException;
import ai.djl.Model;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.NoBatchifyTranslator;
import ai.djl.translate.TranslateException;
import ai.djl.translate.TranslatorContext;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** Classifies one token window with DJL. The owning inspector serializes predictor access. */
final class OnnxSequenceClassifier implements AutoCloseable {

    private final Model model;
    private final Predictor<Encoding, double[]> predictor;

    OnnxSequenceClassifier(OnnxInspectionConfig config, OnnxClassificationConfig classification) {
        Model loaded = null;
        try {
            if (!Files.isRegularFile(config.model())) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            loaded = Model.newInstance("guard-classifier", Device.cpu(), "OnnxRuntime");
            loaded.load(config.model(), null, Map.of(
                    "intraOpNumThreads", Integer.toString(config.intraOpThreads()), "interOpNumThreads", "1"));
            ClassificationTranslator translator = new ClassificationTranslator(loaded.describeInput().keys(), classification);
            predictor = loaded.newPredictor(translator);
            model = loaded;
        } catch (IOException | MalformedModelException | RuntimeException | LinkageError ex) {
            if (loaded != null) {
                loaded.close();
            }
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
    }

    double[] classify(Encoding encoding) {
        try {
            return predictor.predict(encoding);
        } catch (TranslateException ex) {
            throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
        }
    }

    @Override
    public void close() {
        try {
            predictor.close();
        } finally {
            model.close();
        }
    }

    private record ClassificationTranslator(List<String> inputNames, OnnxClassificationConfig config)
            implements NoBatchifyTranslator<Encoding, double[]> {

        @Override
        public NDList processInput(TranslatorContext context, Encoding encoding) {
            NDManager manager = context.getNDManager();
            NDList inputs = new NDList();
            for (String name : inputNames) {
                long[] values = switch (name) {
                    case "input_ids" -> encoding.getIds();
                    case "attention_mask" -> encoding.getAttentionMask();
                    case "token_type_ids" -> encoding.getTypeIds();
                    default -> throw new IllegalArgumentException("Unsupported classifier input");
                };
                NDArray tensor;
                Shape shape = new Shape(1, values.length);
                if (config.tokenInputType() == OnnxClassificationConfig.TokenInputType.INT32) {
                    int[] integers = new int[values.length];
                    for (int i = 0; i < values.length; i++) {
                        integers[i] = Math.toIntExact(values[i]);
                    }
                    tensor = manager.create(integers, shape);
                } else {
                    tensor = manager.create(values, shape);
                }
                tensor.setName(name);
                inputs.add(tensor);
            }
            return inputs;
        }

        @Override
        public double[] processOutput(TranslatorContext context, NDList output) {
            NDArray outputLogits = output.get("logits");
            if (outputLogits == null || outputLogits.getDataType() != DataType.FLOAT32
                    || !outputLogits.getShape().equals(new Shape(1, config.classCount()))) {
                throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
            }
            float[] logits = outputLogits.toFloatArray();
            double maximum = Double.NEGATIVE_INFINITY;
            for (float logit : logits) {
                if (!Float.isFinite(logit)) {
                    throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
                }
                maximum = Math.max(maximum, logit);
            }
            double[] scores = new double[logits.length];
            if (config.activation() == OnnxClassificationConfig.Activation.SOFTMAX) {
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
    }
}
