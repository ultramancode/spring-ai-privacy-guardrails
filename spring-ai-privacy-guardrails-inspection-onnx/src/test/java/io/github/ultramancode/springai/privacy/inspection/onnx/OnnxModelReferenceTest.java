package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.util.JsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class OnnxModelReferenceTest {

    @TempDir Path tempPath;

    // JUnit calls this method through @MethodSource to obtain each case and activation pair.
    static Stream<Arguments> references() throws Exception {
        try (InputStreamReader reader = new InputStreamReader(OnnxModelReferenceTest.class.getResourceAsStream(
                "/public-model-reference.json"), StandardCharsets.UTF_8)) {
            JsonObject reference = JsonUtils.GSON.fromJson(reader, JsonObject.class);
            // The test model is cached in a directory named after its revision.
            assertThat(OnnxTestModel.directory().getFileName().toString())
                    .isEqualTo(reference.get("revision").getAsString());
            List<Arguments> cases = new ArrayList<>();
            for (JsonElement entry : reference.getAsJsonArray("cases")) {
                JsonObject referenceCase = entry.getAsJsonObject();
                for (OnnxClassificationConfig.Activation activation : OnnxClassificationConfig.Activation.values()) {
                    cases.add(Arguments.of(referenceCase.get("name").getAsString(), activation, referenceCase));
                }
            }
            return cases.stream();
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("references")
    void tokenizationAndInspectionMatchPythonReference(
            String caseName, OnnxClassificationConfig.Activation activation, JsonObject referenceCase) throws Exception {
        JsonObject tokenizerConfigJson = JsonUtils.GSON.fromJson(
                Files.readString(OnnxTestModel.directory().resolve("tokenizer_config.json")), JsonObject.class);
        tokenizerConfigJson.add("padding_side", referenceCase.get("direction"));
        tokenizerConfigJson.add("truncation_side", referenceCase.get("direction"));
        Path tokenizerConfigPath = Files.writeString(
                tempPath.resolve("tokenizer_config.json"), tokenizerConfigJson.toString());
        OnnxClassificationConfig classificationConfig = OnnxTestModel.classificationBuilder(
                OnnxTestModel.directory().resolve("tokenizer.json"), tokenizerConfigPath)
                .windowSize(referenceCase.get("windowSize").getAsInt())
                .overlapSize(referenceCase.get("overlapSize").getAsInt())
                .activation(activation)
                // A tiny positive threshold includes all six class scores in the findings.
                .labels(IntStream.range(0, 6).mapToObj(index -> new OnnxClassificationConfig.Label(index,
                        InspectionFinding.Category.POLICY_VIOLATION, "LABEL_" + index, 1e-300)).toList())
                .build();
        OnnxInspectionConfig inspectionConfig = OnnxInspectionConfig.defaults(OnnxTestModel.model());
        String text = referenceCase.get("text").getAsString();
        double[] expectedMaximumScores = new double[6];

        // Compare each token window and class score with the saved Python reference.
        try (TokenWindowTokenizer tokenizer = new TokenWindowTokenizer(classificationConfig);
                OnnxSequenceClassifier classifier = new OnnxSequenceClassifier(inspectionConfig, classificationConfig)) {
            List<Encoding> actualWindows = tokenizer.encodeWindows(text);
            JsonArray expectedWindows = referenceCase.getAsJsonArray("windows");
            assertThat(actualWindows).as(caseName).hasSize(expectedWindows.size());
            for (int windowIndex = 0; windowIndex < actualWindows.size(); windowIndex++) {
                Encoding actualWindow = actualWindows.get(windowIndex);
                JsonObject expectedWindow = expectedWindows.get(windowIndex).getAsJsonObject();
                JsonObject expectedTensors = expectedWindow.getAsJsonObject("tensors");
                assertTensor(expectedTensors, "input_ids", actualWindow.getIds());
                assertTensor(expectedTensors, "attention_mask", actualWindow.getAttentionMask());
                assertTensor(expectedTensors, "token_type_ids", actualWindow.getTypeIds());
                assertTensor(expectedTensors, "special_tokens_mask", actualWindow.getSpecialTokenMask());
                JsonArray expectedScores = expectedWindow.getAsJsonArray(activation.name().toLowerCase(Locale.ROOT));
                double[] actualScores = classifier.classify(actualWindow);
                assertThat(actualScores).hasSize(6);
                for (int labelIndex = 0; labelIndex < 6; labelIndex++) {
                    double expectedScore = expectedScores.get(labelIndex).getAsDouble();
                    assertThat(actualScores[labelIndex]).as("%s window %s label %s", caseName, windowIndex, labelIndex)
                            .isCloseTo(expectedScore, within(1e-4));
                    expectedMaximumScores[labelIndex] = Math.max(expectedMaximumScores[labelIndex], expectedScore);
                }
            }
        }

        // Compare the inspector's per-label maxima with the Python reference.
        try (OnnxContentInspector inspector = new OnnxContentInspector(
                "public-model", inspectionConfig, classificationConfig)) {
            InspectionRequest request = new InspectionRequest(List.of(new ContentSegment("segment",
                    ContentSegment.Role.USER, ContentSegment.PrivacyProcessingStatus.UNKNOWN, text)),
                    new InspectionLimits(1, 131_072, Duration.ofSeconds(30)));
            InspectionResult actualResult = inspector.inspect(request);
            assertThat(actualResult.status()).as(caseName).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(actualResult.completedSegmentIds()).containsExactly("segment");
            assertThat(actualResult.findings()).hasSize(6);
            for (int labelIndex = 0; labelIndex < 6; labelIndex++) {
                String expectedCode = "LABEL_" + labelIndex;
                double expectedScore = expectedMaximumScores[labelIndex];
                assertThat(actualResult.findings()).filteredOn(finding -> finding.code().equals(expectedCode)).singleElement()
                        .satisfies(finding -> assertThat(finding.score()).isCloseTo(expectedScore, within(1e-4)));
            }
        }
    }

    private static void assertTensor(JsonObject expectedTensors, String tensorName, long[] actualValues) throws Exception {
        ByteBuffer actualBuffer = ByteBuffer.allocate(actualValues.length * Long.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (long actualValue : actualValues) {
            actualBuffer.putLong(actualValue);
        }

        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] actualHashBytes = sha256.digest(actualBuffer.array());
        String actualHash = HexFormat.of().formatHex(actualHashBytes);
        String expectedHash = expectedTensors.get(tensorName).getAsString();
        assertThat(actualHash).as(tensorName).isEqualTo(expectedHash);
    }
}
