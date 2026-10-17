package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.util.JsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenWindowTokenizerTest {

    @TempDir Path temp;
    private Path tokenizer;
    private Path tokenizerConfigPath;

    @BeforeEach
    void prepareTokenizer() throws Exception {
        tokenizer = temp.resolve("tokenizer.json");
        tokenizerConfigPath = temp.resolve("tokenizer_config.json");
        Files.copy(OnnxTestModel.directory().resolve("tokenizer.json"), tokenizer);
        Files.writeString(tokenizerConfigPath, "{\"pad_token\":\"[PAD]\"}");
    }

    private OnnxClassificationConfig classification() {
        return OnnxTestModel.classificationBuilder(tokenizer, tokenizerConfigPath)
                .windowSize(8)
                .overlapSize(1)
                .build();
    }

    private JsonObject readTokenizerJson() throws Exception {
        return JsonUtils.GSON.fromJson(Files.readString(tokenizer), JsonObject.class);
    }

    private void savedPadding(String direction, String token, int id, int typeId) throws Exception {
        JsonObject tokenizerJson = readTokenizerJson();
        JsonObject padding = JsonUtils.GSON.fromJson("""
                {"strategy":"BatchLongest","direction":"Right","pad_to_multiple_of":16,
                 "pad_id":0,"pad_type_id":0,"pad_token":"[PAD]"}
                """, JsonObject.class);
        padding.addProperty("direction", direction);
        padding.addProperty("pad_token", token);
        padding.addProperty("pad_id", id);
        padding.addProperty("pad_type_id", typeId);
        tokenizerJson.add("padding", padding);
        Files.writeString(tokenizer, tokenizerJson.toString());
    }

    @Test
    void configOnlyLeftPaddingUsesTheDeclaredTokenAndTypeForShortEmptyAndOverflowWindows() throws Exception {
        Files.writeString(tokenizerConfigPath, """
                {"pad_token":{"content":"[MASK]","special":true},
                 "padding_side":"left","pad_token_type_id":3}
                """);
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            Encoding shortText = windowTokenizer.encodeWindows("Hello world").get(0);
            assertThat(shortText.getIds()).containsExactly(103, 103, 103, 103, 101, 7592, 2088, 102);
            assertThat(shortText.getAttentionMask()).containsExactly(0, 0, 0, 0, 1, 1, 1, 1);
            assertThat(shortText.getTypeIds()).containsExactly(3, 3, 3, 3, 0, 0, 0, 0);
            assertThat(windowTokenizer.encodeWindows("").get(0).getIds()).containsExactly(103, 103, 103, 103, 103, 103, 101, 102);
            List<Encoding> windows = windowTokenizer.encodeWindows("Hello ".repeat(8) + "world");
            assertThat(windows).hasSize(2);
            assertThat(windows.get(0).getIds()).containsExactly(101, 7592, 7592, 7592, 7592, 7592, 7592, 102);
            Encoding tail = windows.get(1);
            assertThat(tail.getIds()).containsExactly(103, 103, 101, 7592, 7592, 7592, 2088, 102);
            assertThat(tail.getAttentionMask()).containsExactly(0, 0, 1, 1, 1, 1, 1, 1);
            assertThat(tail.getTypeIds()).containsExactly(3, 3, 0, 0, 0, 0, 0, 0);
        }
    }

    @Test
    void serializedPaddingRetainsDirectionTokenAndTypeWhenConfigDoesNotOverrideThem() throws Exception {
        savedPadding("Left", "[MASK]", 103, 3);
        Files.writeString(tokenizerConfigPath, "{}");
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            Encoding encoded = windowTokenizer.encodeWindows("Hello world").get(0);
            assertThat(encoded.getIds()).containsExactly(103, 103, 103, 103, 101, 7592, 2088, 102);
            assertThat(encoded.getAttentionMask()).containsExactly(0, 0, 0, 0, 1, 1, 1, 1);
            assertThat(encoded.getTypeIds()).containsExactly(3, 3, 3, 3, 0, 0, 0, 0);
        }
    }

    @Test
    void explicitConfigOverridesSerializedPaddingInBothDirections() throws Exception {
        savedPadding("Left", "[PAD]", 0, 3);
        Files.writeString(tokenizerConfigPath, "{\"padding_side\":\"right\",\"pad_token\":\"[MASK]\",\"pad_token_type_id\":2}");
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            Encoding encoded = windowTokenizer.encodeWindows("Hello world").get(0);
            assertThat(encoded.getIds()).containsExactly(101, 7592, 2088, 102, 103, 103, 103, 103);
            assertThat(encoded.getTypeIds()).containsExactly(0, 0, 0, 0, 2, 2, 2, 2);
        }
        savedPadding("Right", "[PAD]", 0, 0);
        Files.writeString(tokenizerConfigPath, "{\"padding_side\":\"left\",\"pad_token\":\"[MASK]\",\"pad_token_type_id\":2}");
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            assertThat(windowTokenizer.encodeWindows("Hello world").get(0).getIds()).containsExactly(103, 103, 103, 103, 101, 7592, 2088, 102);
        }
    }

    @Test
    void unigramVocabularyResolvesPaddingWithoutAnAddedTokenOrPaddingBlock() throws Exception {
        JsonObject tokenizerJson = readTokenizerJson();
        JsonObject model = tokenizerJson.getAsJsonObject("model");
        JsonArray vocabulary = new JsonArray();
        model.getAsJsonObject("vocab").entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getValue().getAsInt()))
                .forEach(entry -> {
                    JsonArray piece = new JsonArray();
                    piece.add(entry.getKey());
                    piece.add(-1.0);
                    vocabulary.add(piece);
                });
        model.remove("unk_token");
        model.addProperty("type", "Unigram");
        model.addProperty("unk_id", 100);
        model.addProperty("byte_fallback", false);
        model.add("vocab", vocabulary);
        tokenizerJson.getAsJsonArray("added_tokens").remove(0);
        Files.writeString(tokenizer, tokenizerJson.toString());
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            assertThat(windowTokenizer.encodeWindows("Hello").get(0).getIds()).containsExactly(101, 7592, 102, 0, 0, 0, 0, 0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"pad_token\":\"[UNREGISTERED_TEST_PAD]\"}",
            "{\"pad_token\":\"[PAD]\",\"padding_side\":\"center\"}",
            "{\"pad_token\":\"[PAD]\",\"pad_token_type_id\":-1}",
            "{\"pad_token\":\"[PAD]\",\"pad_token_type_id\":0.5}",
            "{\"pad_token\":\"[PAD]\",\"truncation_side\":\"center\"}"
    })
    void invalidOrUnresolvedConfigurationIsRejectedBeforeInference(String configuration) throws Exception {
        Files.writeString(tokenizerConfigPath, configuration);
        assertThatThrownBy(() -> new TokenWindowTokenizer(classification()))
                .isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CONFIGURATION))
                .hasNoCause().hasMessageNotContaining(temp.toString());
    }

    static Stream<Arguments> specialTokenBoundaries() {
        String bert = "{\"type\":\"BertProcessing\",\"sep\":[\"[SEP]\",102],\"cls\":[\"[CLS]\",101]}";
        String roberta = "{\"type\":\"RobertaProcessing\",\"sep\":[\"[SEP]\",102],\"cls\":[\"[CLS]\",101],"
                + "\"trim_offsets\":true,\"add_prefix_space\":true}";
        String template = """
                {"type":"TemplateProcessing",
                 "single":[{"SpecialToken":{"id":"pair","type_id":0}},
                           {"Sequence":{"id":"A","type_id":0}},
                           {"SpecialToken":{"id":"pair","type_id":0}}],
                 "pair":[],"special_tokens":{"pair":{"id":"pair","ids":[101,102],"tokens":["[CLS]","[SEP]"]}}}
                """;
        return Stream.of(Arguments.of("bert", bert, 2), Arguments.of("roberta", roberta, 2),
                Arguments.of("multi-id-template", template, 4),
                Arguments.of("nested", "{\"type\":\"Sequence\",\"processors\":[" + bert
                        + ",{\"type\":\"Sequence\",\"processors\":[" + roberta + "]}]}", 4));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("specialTokenBoundaries")
    void specialTokensLeaveRoomForContentAndOverlap(String name, String processor, int specialTokens) throws Exception {
        JsonObject tokenizerJson = readTokenizerJson();
        tokenizerJson.add("post_processor", JsonUtils.GSON.fromJson(processor, JsonObject.class));
        Files.writeString(tokenizer, tokenizerJson.toString());
        OnnxClassificationConfig invalid = OnnxTestModel.classificationBuilder(tokenizer, tokenizerConfigPath)
                .windowSize(specialTokens + 2).overlapSize(2).build();
        assertThatThrownBy(() -> new TokenWindowTokenizer(invalid)).as(name)
                .isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CONFIGURATION));
        OnnxClassificationConfig valid = OnnxTestModel.classificationBuilder(tokenizer, tokenizerConfigPath)
                .windowSize(specialTokens + 2).overlapSize(1).build();
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(valid)) {
            assertThat(windowTokenizer.encodeWindows("Hello world Hello")).as(name).hasSize(2)
                    .allSatisfy(window -> assertThat(window.getIds()).hasSize(specialTokens + 2));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void processorsWithoutSpecialTokensAllowSingleTokenWindows(boolean byteLevel) throws Exception {
        JsonObject tokenizerJson = readTokenizerJson();
        tokenizerJson.add("post_processor", byteLevel ? JsonUtils.GSON.fromJson("""
                {"type":"ByteLevel","add_prefix_space":true,"trim_offsets":true,"use_regex":true}
                """, JsonObject.class) : null);
        Files.writeString(tokenizer, tokenizerJson.toString());
        OnnxClassificationConfig config = OnnxTestModel.classificationBuilder(tokenizer, tokenizerConfigPath)
                .windowSize(1).overlapSize(0).build();
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(config)) {
            List<Encoding> windows = windowTokenizer.encodeWindows("Hello world");
            assertThat(windows).hasSize(2);
            assertThat(windows.get(0).getIds()).containsExactly(7592);
            assertThat(windows.get(1).getIds()).containsExactly(2088);
        }
    }
}
