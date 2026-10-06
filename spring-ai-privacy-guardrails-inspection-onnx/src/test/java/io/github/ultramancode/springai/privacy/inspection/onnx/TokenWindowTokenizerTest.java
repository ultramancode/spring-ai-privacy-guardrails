package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.util.JsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenWindowTokenizerTest {

    @TempDir Path temp;
    private Path tokenizer;
    private Path settings;

    @BeforeEach
    void prepareTokenizer() throws Exception {
        tokenizer = temp.resolve("tokenizer.json");
        settings = temp.resolve("tokenizer_config.json");
        try (InputStream input = getClass().getResourceAsStream("/tokenizer.json")) {
            Files.write(tokenizer, input.readAllBytes());
        }
        Files.writeString(settings, "{\"pad_token\":\"[PAD]\"}");
    }

    private OnnxClassificationConfig classification() {
        return OnnxClassificationConfig.builder()
                .tokenizer(tokenizer)
                .tokenizerConfig(settings)
                .maxTokens(8)
                .overlapTokens(1)
                .logitCount(2)
                .activation(OnnxClassificationConfig.Activation.SOFTMAX)
                .labels(List.of(new OnnxClassificationConfig.Label(1,
                        InspectionFinding.Category.PROMPT_ATTACK, "MALICIOUS", 0.5)))
                .build();
    }

    private JsonObject definition() throws Exception {
        return JsonUtils.GSON.fromJson(Files.readString(tokenizer), JsonObject.class);
    }

    private void savedPadding(String direction, String token, int id, int typeId) throws Exception {
        JsonObject definition = definition();
        JsonObject padding = JsonUtils.GSON.fromJson("""
                {"strategy":"BatchLongest","direction":"Right","pad_to_multiple_of":16,
                 "pad_id":0,"pad_type_id":0,"pad_token":"[PAD]"}
                """, JsonObject.class);
        padding.addProperty("direction", direction);
        padding.addProperty("pad_token", token);
        padding.addProperty("pad_id", id);
        padding.addProperty("pad_type_id", typeId);
        definition.add("padding", padding);
        Files.writeString(tokenizer, definition.toString());
    }

    @Test
    void configOnlyLeftPaddingUsesTheDeclaredTokenAndTypeForShortEmptyAndOverflowWindows() throws Exception {
        Files.writeString(settings, """
                {"pad_token":{"content":"word7","special":true},
                 "padding_side":"left","pad_token_type_id":3}
                """);
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            Encoding shortText = windowTokenizer.encodeWindows("hello world").get(0);
            assertThat(shortText.getIds()).containsExactly(7, 7, 7, 7, 2, 4, 5, 3);
            assertThat(shortText.getAttentionMask()).containsExactly(0, 0, 0, 0, 1, 1, 1, 1);
            assertThat(shortText.getTypeIds()).containsExactly(3, 3, 3, 3, 0, 0, 0, 0);
            assertThat(windowTokenizer.encodeWindows("").get(0).getIds()).containsExactly(7, 7, 7, 7, 7, 7, 2, 3);
            List<Encoding> windows = windowTokenizer.encodeWindows("hello ".repeat(8) + "world");
            assertThat(windows).hasSize(2);
            assertThat(windows.get(0).getIds()).containsExactly(2, 4, 4, 4, 4, 4, 4, 3);
            Encoding tail = windows.get(1);
            assertThat(tail.getIds()).containsExactly(7, 7, 2, 4, 4, 4, 5, 3);
            assertThat(tail.getAttentionMask()).containsExactly(0, 0, 1, 1, 1, 1, 1, 1);
            assertThat(tail.getTypeIds()).containsExactly(3, 3, 0, 0, 0, 0, 0, 0);
        }
    }

    @Test
    void serializedPaddingRetainsDirectionTokenAndTypeWhenConfigDoesNotOverrideThem() throws Exception {
        savedPadding("Left", "word7", 7, 3);
        Files.writeString(settings, "{}");
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            Encoding encoded = windowTokenizer.encodeWindows("hello world").get(0);
            assertThat(encoded.getIds()).containsExactly(7, 7, 7, 7, 2, 4, 5, 3);
            assertThat(encoded.getAttentionMask()).containsExactly(0, 0, 0, 0, 1, 1, 1, 1);
            assertThat(encoded.getTypeIds()).containsExactly(3, 3, 3, 3, 0, 0, 0, 0);
        }
    }

    @Test
    void explicitConfigOverridesSerializedPaddingInBothDirections() throws Exception {
        savedPadding("Left", "[PAD]", 0, 3);
        Files.writeString(settings, "{\"padding_side\":\"right\",\"pad_token\":\"word7\",\"pad_token_type_id\":2}");
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            Encoding encoded = windowTokenizer.encodeWindows("hello world").get(0);
            assertThat(encoded.getIds()).containsExactly(2, 4, 5, 3, 7, 7, 7, 7);
            assertThat(encoded.getTypeIds()).containsExactly(0, 0, 0, 0, 2, 2, 2, 2);
        }
        savedPadding("Right", "[PAD]", 0, 0);
        Files.writeString(settings, "{\"padding_side\":\"left\",\"pad_token\":\"word7\",\"pad_token_type_id\":2}");
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            assertThat(windowTokenizer.encodeWindows("hello world").get(0).getIds()).containsExactly(7, 7, 7, 7, 2, 4, 5, 3);
        }
    }

    @Test
    void unigramVocabularyResolvesPaddingWithoutAnAddedTokenOrPaddingBlock() throws Exception {
        JsonObject definition = definition();
        JsonObject model = definition.getAsJsonObject("model");
        JsonArray vocabulary = new JsonArray();
        model.getAsJsonObject("vocab").entrySet().stream()
                .sorted(java.util.Comparator.comparingInt(entry -> entry.getValue().getAsInt()))
                .forEach(entry -> {
                    JsonArray piece = new JsonArray();
                    piece.add(entry.getKey());
                    piece.add(-1.0);
                    vocabulary.add(piece);
                });
        model.remove("unk_token");
        model.addProperty("type", "Unigram");
        model.addProperty("unk_id", 1);
        model.addProperty("byte_fallback", false);
        model.add("vocab", vocabulary);
        definition.getAsJsonArray("added_tokens").remove(0);
        Files.writeString(tokenizer, definition.toString());
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            assertThat(windowTokenizer.encodeWindows("hello").get(0).getIds()).containsExactly(2, 4, 3, 0, 0, 0, 0, 0);
        }
    }

    @Test
    void configuredTruncationDirectionKeepsFullCoverageAndPaddedOverflow() throws Exception {
        Files.writeString(settings, "{\"pad_token\":\"[PAD]\",\"padding_side\":\"left\",\"truncation_side\":\"left\"}");
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            List<Encoding> windows = windowTokenizer.encodeWindows("hello ".repeat(8) + "world");
            assertThat(windows).hasSize(2);
            assertThat(windows.get(0).getIds()).containsExactly(2, 4, 4, 4, 4, 4, 5, 3);
            assertThat(windows.get(1).getIds()).containsExactly(0, 0, 2, 4, 4, 4, 4, 3);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}", "{\"pad_token\":null}", "{\"pad_token\":\"missing\"}",
            "{\"pad_token\":{}}", "{\"pad_token\":7}",
            "{\"pad_token\":\"[PAD]\",\"padding_side\":\"center\"}",
            "{\"pad_token\":\"[PAD]\",\"padding_side\":null}",
            "{\"pad_token\":\"[PAD]\",\"pad_token_type_id\":-1}",
            "{\"pad_token\":\"[PAD]\",\"pad_token_type_id\":0.5}",
            "{\"pad_token\":\"[PAD]\",\"pad_token_type_id\":4294967296}",
            "{\"pad_token\":\"[PAD]\",\"pad_token_type_id\":\"0\"}",
            "{\"pad_token\":\"[PAD]\",\"truncation_side\":\"center\"}"
    })
    void invalidOrUnresolvedConfigurationIsRejectedBeforeInference(String configuration) throws Exception {
        Files.writeString(settings, configuration);
        assertThatThrownBy(() -> new TokenWindowTokenizer(classification()))
                .isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failure()).isEqualTo(InspectionFailureCode.CONFIGURATION))
                .hasNoCause().hasMessageNotContaining(temp.toString());
    }

    @Test
    void paddingIdComesFromTheVocabularyEvenWhenTheSavedIdIsStale() throws Exception {
        savedPadding("Left", "[PAD]", 7, 0);
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            Encoding window = windowTokenizer.encodeWindows("hello").get(0);
            assertThat(window.getIds()).containsExactly(0, 0, 0, 0, 0, 2, 4, 3);
        }
    }
}
