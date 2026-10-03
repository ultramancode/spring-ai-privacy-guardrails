package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Independent Python/Java parity on explicitly provisioned artifacts. No detection-quality claims. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "INSPECTION_ONNX_REFERENCE", matches = ".+")
class OnnxClassifierLiveTest {

    @Test
    void everyWindowTensorAndScoreAndTheAggregatedResultMatchPython() throws Exception {
        Path referencePath = Path.of(System.getenv("INSPECTION_ONNX_REFERENCE")).toAbsolutePath();
        Properties reference = new Properties();
        try (InputStream input = Files.newInputStream(referencePath)) {
            reference.load(input);
        }
        Path directory = referencePath.getParent();
        Path model = verifiedArtifact(directory, "model", reference);
        Path tokenizer = verifiedArtifact(directory, "tokenizer", reference);
        Path tokenizerConfig = verifiedArtifact(directory, "tokenizerConfig", reference);
        int classes = Integer.parseInt(reference.getProperty("class.count"));
        List<OnnxClassificationConfig.Label> labels = new ArrayList<>();
        for (int i = 0; i < classes; i++) {
            // Numeric labels exercise every head without implying product-specific label semantics.
            labels.add(new OnnxClassificationConfig.Label(i, InspectionFinding.Category.POLICY_VIOLATION,
                    "LABEL_" + i, 1e-300));
        }
        OnnxClassificationConfig classification = new OnnxClassificationConfig(tokenizer, tokenizerConfig,
                Integer.parseInt(reference.getProperty("window.tokens")),
                Integer.parseInt(reference.getProperty("overlap.tokens")), classes,
                OnnxClassificationConfig.Activation.valueOf(reference.getProperty("activation")), labels,
                OnnxClassificationConfig.TokenInputType.valueOf(reference.getProperty("token.type", "INT64")));
        OnnxInspectionConfig config = OnnxInspectionConfig.defaults(model);
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification);
                OnnxSequenceClassifier classifier = new OnnxSequenceClassifier(config, classification);
                OnnxContentInspector inspector = new OnnxContentInspector("parity", config, classification)) {
            int cases = Integer.parseInt(reference.getProperty("cases"));
            for (int index = 0; index < cases; index++) {
                String prefix = "case." + index + ".";
                String text = new String(Base64.getDecoder().decode(reference.getProperty(prefix + "text")), StandardCharsets.UTF_8);
                List<Encoding> windows = windowTokenizer.encodeWindows(text);
                assertThat(windows).as(prefix).hasSize(Integer.parseInt(reference.getProperty(prefix + "windows")));
                double[] maximum = new double[classes];
                for (int window = 0; window < windows.size(); window++) {
                    Encoding encoded = windows.get(window);
                    String windowPrefix = prefix + "window." + window + ".";
                    assertTensor(reference, windowPrefix, "input_ids", encoded.getIds());
                    assertTensor(reference, windowPrefix, "attention_mask", encoded.getAttentionMask());
                    assertTensor(reference, windowPrefix, "token_type_ids", encoded.getTypeIds());
                    double[] scores = classifier.classify(encoded);
                    String[] expected = reference.getProperty(windowPrefix + "scores").split(",");
                    for (int label = 0; label < classes; label++) {
                        assertThat(scores[label]).as(windowPrefix + label)
                                .isCloseTo(Double.parseDouble(expected[label]), within(1e-4));
                        maximum[label] = Math.max(maximum[label], Double.parseDouble(expected[label]));
                    }
                }
                InspectionResult result = inspector.inspect(request(text));
                assertThat(result.status()).as(prefix + result.failure()).isEqualTo(InspectionResult.Status.COMPLETED);
                assertThat(result.completedSegmentIds()).containsExactly("synthetic");
                int expectedCount = 0;
                for (int label = 0; label < classes; label++) {
                    if (maximum[label] >= 1e-300) {
                        final String code = "LABEL_" + label;
                        final double score = maximum[label];
                        assertThat(result.findings()).filteredOn(finding -> finding.code().equals(code))
                                .singleElement().satisfies(finding -> assertThat(finding.score()).isCloseTo(score, within(1e-4)));
                        expectedCount++;
                    }
                }
                assertThat(result.findings()).hasSize(expectedCount);
            }
            assertThat(Integer.parseInt(reference.getProperty("case.7.windows"))).isGreaterThan(1);
        }
    }

    private InspectionRequest request(String text) {
        return new InspectionRequest(List.of(new ContentSegment("synthetic", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.UNKNOWN, text)),
                new InspectionLimits(1, 131_072, 256, Duration.ofMinutes(2)));
    }

    private static Path verifiedArtifact(Path directory, String name, Properties reference) throws Exception {
        Path file = directory.resolve(reference.getProperty(name + ".file"));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        assertThat(HexFormat.of().formatHex(digest.digest())).isEqualTo(reference.getProperty(name + ".sha256"));
        return file;
    }

    private static void assertTensor(Properties reference, String prefix, String name, long[] values) throws Exception {
        String expected = reference.getProperty(prefix + name + ".sha256");
        if (expected != null) {
            ByteBuffer buffer = ByteBuffer.allocate(values.length * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
            for (long value : values) {
                buffer.putLong(value);
            }
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.array())))
                    .as(prefix + name).isEqualTo(expected);
        }
    }
}
