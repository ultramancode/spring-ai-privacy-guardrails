package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.onnxruntime.OrtEnvironment;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Real exports validate execution diversity; fixture profiles are not additional supported products. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "INSPECTION_ONNX_MODELS", matches = ".+")
class OnnxModelLiveTest {
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "deberta-v3-base-prompt-injection-v2, 512, 2",
            "distilbert-prompt-injection-onnx, 512, 2",
            "modernbert-base-prompt-injection-detection, 2048, 2",
            "agent-guard-modernbert-base, 1024, 17"
    })
    void tokenizerAndEveryOutputScoreMatchPython(String name, int windowTokens, int classes) throws Exception {
        Path directory = Path.of(System.getenv("INSPECTION_ONNX_MODELS")).resolve(name);
        Path graph = directory.resolve("model.onnx");
        Path tokenizer = directory.resolve("tokenizer.json");
        Properties reference = new Properties();
        try (InputStream input = Files.newInputStream(directory.resolve("reference.properties"))) {
            reference.load(input);
        }
        assertThat(PromptGuardLiveTest.fileHash(graph)).isEqualTo(reference.getProperty("model.sha256"));
        assertThat(PromptGuardLiveTest.fileHash(tokenizer)).isEqualTo(reference.getProperty("tokenizer.sha256"));
        assertThat(OrtEnvironment.getEnvironment().getVersion()).isEqualTo(reference.getProperty("python.onnxruntime"));
        assertThat(windowTokens).isEqualTo(Integer.parseInt(reference.getProperty("window.tokens")));
        OnnxInspectionModel model = adapter(name, tokenizer, windowTokens, classes);
        try (OnnxContentInspector inspector = new OnnxContentInspector(OnnxInspectionConfig.defaults(graph), model)) {
            int cases = Integer.parseInt(reference.getProperty("cases"));
            for (int index = 0; index < cases; index++) {
                String prefix = "case." + index + ".";
                String input = new String(Base64.getDecoder().decode(reference.getProperty(prefix + "text")),
                        StandardCharsets.UTF_8);
                List<Map<String, long[]>> windows = model.encode(input, 16);
                assertThat(windows).as(name + " " + prefix).hasSize(Integer.parseInt(reference.getProperty(prefix + "windows")));
                InspectionRequest request = new InspectionRequest(List.of(new ContentSegment("synthetic",
                        ContentSegment.Source.USER, ContentSegment.Role.USER, ContentSegment.Representation.RAW, input)),
                        new InspectionLimits(4, 131072, 16, Duration.ofMinutes(2)));
                InspectionResult result = inspector.inspect(request);
                assertThat(result.status()).as(name + " " + prefix + " failure=" + result.failure())
                        .isEqualTo(InspectionResult.Status.COMPLETED);
                assertThat(result.inspectedSegmentIds()).containsExactly("synthetic");
                int findingIndex = 0;
                for (int window = 0; window < windows.size(); window++) {
                    String windowPrefix = prefix + "window." + window + ".";
                    for (Map.Entry<String, long[]> tensor : windows.get(window).entrySet()) {
                        assertThat(PromptGuardLiveTest.tensorHash(tensor.getValue())).as(tensor.getKey())
                                .isEqualTo(reference.getProperty(windowPrefix + tensor.getKey() + ".sha256"));
                        assertThat(tensor.getValue()).hasSize(windowTokens);
                    }
                    for (String score : reference.getProperty(windowPrefix + "scores").split(",")) {
                        assertThat(result.findings().get(findingIndex++).score()).as(name + " " + windowPrefix)
                                .isCloseTo(Double.parseDouble(score), within(1e-4));
                    }
                }
                assertThat(result.findings()).hasSize(findingIndex);
            }
            assertThat(Integer.parseInt(reference.getProperty("case.7.windows"))).isGreaterThan(1);
        }
    }

    private static OnnxInspectionModel adapter(String name, Path tokenizer, int windowTokens, int classes) {
        if (name.equals("deberta-v3-base-prompt-injection-v2")) {
            return new ProtectAiDebertaV2Model(tokenizer, 1e-300, 64);
        }
        List<HuggingFaceSequenceClassifier.Label> labels = new ArrayList<>();
        if (classes == 2) {
            labels.add(new HuggingFaceSequenceClassifier.Label(1, InspectionFinding.Category.PROMPT_INJECTION, "INJECTION"));
        } else {
            // Numeric fixture labels verify every sigmoid head without publishing unverified category semantics.
            for (int index = 0; index < classes; index++) {
                labels.add(new HuggingFaceSequenceClassifier.Label(index, InspectionFinding.Category.PROMPT_ATTACK,
                        "LABEL_" + index));
            }
        }
        return new HuggingFaceSequenceClassifier(name, tokenizer, tokenizer.resolveSibling("tokenizer_config.json"),
                windowTokens, 64, classes,
                classes == 2 ? HuggingFaceSequenceClassifier.Activation.SOFTMAX : HuggingFaceSequenceClassifier.Activation.SIGMOID,
                labels, 1e-300);
    }
}
