package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailure;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Shared tokenizer and sequence-classification logic for the supplied model adapters. */
class HuggingFaceSequenceClassifier implements OnnxInspectionModel {
    enum Activation {
        SOFTMAX,
        SIGMOID
    }

    record Label(int index, InspectionFinding.Category category, String code) { }

    private final String providerId;
    private final HuggingFaceTokenizer tokenizer;
    private final int maxTokens;
    private final int outputClassCount;
    private final Activation activation;
    private final List<Label> labels;
    private final double threshold;
    private Set<String> inputNames;

    HuggingFaceSequenceClassifier(String providerId, Path tokenizerPath, int maxTokens, int overlapTokens,
            int outputClassCount, Activation activation, List<Label> labels, double threshold) {
        this(providerId, tokenizerPath, null, maxTokens, overlapTokens, outputClassCount, activation, labels, threshold);
    }

    HuggingFaceSequenceClassifier(String providerId, Path tokenizerPath, Path tokenizerConfigPath,
            int maxTokens, int overlapTokens, int outputClassCount, Activation activation,
            List<Label> labels, double threshold) {
        if (maxTokens < 4 || overlapTokens < 0 || overlapTokens > maxTokens / 2) {
            throw new IllegalArgumentException("Invalid token window or overlap");
        }
        if (!Double.isFinite(threshold) || threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("threshold must be finite, greater than 0 and at most 1");
        }
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.maxTokens = maxTokens;
        this.outputClassCount = outputClassCount;
        this.activation = Objects.requireNonNull(activation, "activation");
        this.labels = List.copyOf(labels);
        this.threshold = threshold;
        for (Label label : labels) {
            if (label.index() < 0 || label.index() >= outputClassCount) {
                throw new IllegalArgumentException("Label index is outside the classifier output");
            }
        }
        if (tokenizerConfigPath != null && !Files.isRegularFile(tokenizerConfigPath)) {
            throw new InspectionException(InspectionFailure.CONFIGURATION);
        }
        try {
            Map<String, String> options = Map.of(
                    "maxLength", Integer.toString(maxTokens),
                    "modelMaxLength", Integer.toString(maxTokens),
                    "padding", "max_length", "truncation", "true",
                    "stride", Integer.toString(overlapTokens),
                    "withOverflowingTokens", "true", "addSpecialTokens", "true");
            this.tokenizer = tokenizerConfigPath == null
                    ? HuggingFaceTokenizer.newInstance(tokenizerPath, options)
                    : HuggingFaceTokenizer.newInstance(tokenizerPath, tokenizerConfigPath.toString(), options);
        } catch (Exception ex) {
            throw new InspectionException(InspectionFailure.CONFIGURATION);
        }
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public void validate(Map<String, NodeInfo> inputs, Map<String, NodeInfo> outputs) {
        Set<String> modelInputNames = inputs.keySet();
        if (!modelInputNames.containsAll(Set.of("input_ids", "attention_mask"))
                || !Set.of("input_ids", "attention_mask", "token_type_ids").containsAll(modelInputNames)) {
            throw new InspectionException(InspectionFailure.CONFIGURATION);
        }
        for (NodeInfo input : inputs.values()) {
            TensorInfo tensor = (TensorInfo) input.getInfo();
            if (tensor.getShape()[1] > 0 && tensor.getShape()[1] != maxTokens) {
                throw new InspectionException(InspectionFailure.CONFIGURATION);
            }
        }
        NodeInfo output = outputs.get("logits");
        if (output == null || !(output.getInfo() instanceof TensorInfo tensor)
                || tensor.type != OnnxJavaType.FLOAT || tensor.getShape().length != 2
                || tensor.getShape()[0] > 1
                || (tensor.getShape()[1] > 0 && tensor.getShape()[1] != outputClassCount)) {
            throw new InspectionException(InspectionFailure.CONFIGURATION);
        }
        inputNames = Set.copyOf(modelInputNames);
    }

    @Override
    public List<Map<String, long[]>> encode(String text, int maxWindows) {
        if (maxWindows < 1) {
            throw new InspectionException(InspectionFailure.LIMIT_EXCEEDED);
        }
        Encoding first = tokenizer.encode(text);
        Encoding[] overflow = first.getOverflowing();
        if (overflow.length >= maxWindows) {
            throw new InspectionException(InspectionFailure.LIMIT_EXCEEDED);
        }
        List<Map<String, long[]>> windows = new ArrayList<>();
        windows.add(inputs(first));
        for (Encoding encoding : overflow) {
            windows.add(inputs(encoding));
        }
        return windows;
    }

    private Map<String, long[]> inputs(Encoding encoding) {
        if (encoding.getIds().length == 0 || encoding.getIds().length > maxTokens) {
            throw new InspectionException(InspectionFailure.MODEL_ERROR);
        }
        Map<String, long[]> inputValues = new HashMap<>();
        inputValues.put("input_ids", encoding.getIds());
        inputValues.put("attention_mask", encoding.getAttentionMask());
        if (inputNames.contains("token_type_ids")) {
            inputValues.put("token_type_ids", encoding.getTypeIds());
        }
        return inputValues;
    }

    @Override
    public List<InspectionFinding> decode(String segmentId, OrtSession.Result outputs) throws OrtException {
        Object logitsValue = outputs.get("logits")
                .orElseThrow(() -> new InspectionException(InspectionFailure.MODEL_ERROR)).getValue();
        if (!(logitsValue instanceof float[][] batch) || batch.length != 1 || batch[0].length != outputClassCount) {
            throw new InspectionException(InspectionFailure.MODEL_ERROR);
        }
        float[] logits = batch[0];
        double maximumLogit = Double.NEGATIVE_INFINITY;
        for (float logit : logits) {
            if (!Float.isFinite(logit)) {
                throw new InspectionException(InspectionFailure.MODEL_ERROR);
            }
            maximumLogit = Math.max(maximumLogit, logit);
        }
        double softmaxDenominator = 0;
        if (activation == Activation.SOFTMAX) {
            for (float logit : logits) {
                softmaxDenominator += Math.exp(logit - maximumLogit);
            }
        }
        List<InspectionFinding> findings = new ArrayList<>();
        for (Label label : labels) {
            double score = activation == Activation.SIGMOID
                    ? 1.0 / (1.0 + Math.exp(-(double) logits[label.index()]))
                    : Math.exp(logits[label.index()] - maximumLogit) / softmaxDenominator;
            if (score >= threshold) {
                findings.add(new InspectionFinding(segmentId, label.category(), label.code(), score));
            }
        }
        return findings;
    }

    @Override
    public void close() {
        tokenizer.close();
    }
}
