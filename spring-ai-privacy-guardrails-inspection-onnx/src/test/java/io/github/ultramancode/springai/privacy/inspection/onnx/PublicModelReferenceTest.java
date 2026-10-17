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

class PublicModelReferenceTest {

    @TempDir Path temp;

    static Stream<Arguments> references() throws Exception {
        try (InputStreamReader reader = new InputStreamReader(PublicModelReferenceTest.class.getResourceAsStream(
                "/public-model-reference.json"), StandardCharsets.UTF_8)) {
            JsonObject reference = JsonUtils.GSON.fromJson(reader, JsonObject.class);
            assertThat(PublicModel.directory().getFileName().toString())
                    .isEqualTo(reference.get("revision").getAsString());
            List<Arguments> cases = new ArrayList<>();
            for (JsonElement entry : reference.getAsJsonArray("cases")) {
                JsonObject item = entry.getAsJsonObject();
                for (OnnxClassificationConfig.Activation activation : OnnxClassificationConfig.Activation.values()) {
                    cases.add(Arguments.of(item.get("name").getAsString(), activation, item));
                }
            }
            return cases.stream();
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("references")
    void tokenWindowsScoresAndAggregatedFindingsMatchPython(
            String name, OnnxClassificationConfig.Activation activation, JsonObject expected) throws Exception {
        JsonObject settings = JsonUtils.GSON.fromJson(
                Files.readString(PublicModel.directory().resolve("tokenizer_config.json")), JsonObject.class);
        settings.add("padding_side", expected.get("direction"));
        settings.add("truncation_side", expected.get("direction"));
        Path configFile = Files.writeString(temp.resolve("tokenizer_config.json"), settings.toString());
        OnnxClassificationConfig classification = PublicModel.classification(
                PublicModel.directory().resolve("tokenizer.json"), configFile)
                .maxTokens(expected.get("maxTokens").getAsInt())
                .overlapTokens(expected.get("overlapTokens").getAsInt())
                .activation(activation)
                .labels(IntStream.range(0, 5).mapToObj(index -> new OnnxClassificationConfig.Label(index,
                        InspectionFinding.Category.POLICY_VIOLATION, "LABEL_" + index, 1e-300)).toList())
                .build();
        OnnxInspectionConfig config = OnnxInspectionConfig.defaults(PublicModel.model());
        try (TokenWindowTokenizer tokenizer = new TokenWindowTokenizer(classification);
                OnnxSequenceClassifier classifier = new OnnxSequenceClassifier(config, classification);
                OnnxContentInspector inspector = new OnnxContentInspector("public-model", config, classification)) {
            String text = expected.get("text").getAsString();
            List<Encoding> actual = tokenizer.encodeWindows(text);
            JsonArray windows = expected.getAsJsonArray("windows");
            assertThat(actual).as(name).hasSize(windows.size());
            double[] maximum = new double[5];
            for (int index = 0; index < actual.size(); index++) {
                Encoding encoding = actual.get(index);
                JsonObject window = windows.get(index).getAsJsonObject();
                JsonObject tensors = window.getAsJsonObject("tensors");
                assertTensor(tensors, "input_ids", encoding.getIds());
                assertTensor(tensors, "attention_mask", encoding.getAttentionMask());
                assertTensor(tensors, "token_type_ids", encoding.getTypeIds());
                assertTensor(tensors, "special_tokens_mask", encoding.getSpecialTokenMask());
                JsonArray scores = window.getAsJsonArray(activation.name().toLowerCase(Locale.ROOT));
                double[] actualScores = classifier.classify(encoding);
                assertThat(actualScores).hasSize(5);
                for (int label = 0; label < 5; label++) {
                    double score = scores.get(label).getAsDouble();
                    assertThat(actualScores[label]).as("%s window %s label %s", name, index, label)
                            .isCloseTo(score, within(1e-4));
                    maximum[label] = Math.max(maximum[label], score);
                }
            }
            InspectionRequest request = new InspectionRequest(List.of(new ContentSegment("segment",
                    ContentSegment.Role.USER, ContentSegment.PrivacyProcessingStatus.UNKNOWN, text)),
                    new InspectionLimits(1, 131_072, Duration.ofSeconds(30)));
            InspectionResult result = inspector.inspect(request);
            assertThat(result.status()).as(name).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.completedSegmentIds()).containsExactly("segment");
            assertThat(result.findings()).hasSize(5);
            for (int label = 0; label < 5; label++) {
                String code = "LABEL_" + label;
                double score = maximum[label];
                assertThat(result.findings()).filteredOn(finding -> finding.code().equals(code)).singleElement()
                        .satisfies(finding -> assertThat(finding.score()).isCloseTo(score, within(1e-4)));
            }
        }
    }

    private static void assertTensor(JsonObject expected, String name, long[] values) throws Exception {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (long value : values) {
            bytes.putLong(value);
        }
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()));
        assertThat(digest).as(name).isEqualTo(expected.get(name).getAsString());
    }
}
