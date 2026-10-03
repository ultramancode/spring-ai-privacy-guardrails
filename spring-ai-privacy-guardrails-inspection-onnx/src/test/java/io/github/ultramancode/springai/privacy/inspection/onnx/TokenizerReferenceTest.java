package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.util.JsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Expected tensors come from AutoTokenizer, not the Java adapter's preprocessing. */
class TokenizerReferenceTest {

    @TempDir Path temp;

    static Stream<Arguments> references() throws Exception {
        try (var reader = new InputStreamReader(TokenizerReferenceTest.class.getResourceAsStream(
                "/tokenizer-reference.json"), StandardCharsets.UTF_8)) {
            JsonObject reference = JsonUtils.GSON.fromJson(reader, JsonObject.class);
            List<Arguments> cases = new ArrayList<>();
            for (var fixture : reference.getAsJsonArray("fixtures")) {
                JsonObject item = fixture.getAsJsonObject();
                for (var entry : item.getAsJsonArray("cases")) {
                    cases.add(Arguments.of(item.get("name").getAsString(), item,
                            entry.getAsJsonObject(), reference.get("windowTokens").getAsInt(),
                            reference.get("overlapTokens").getAsInt()));
                }
            }
            return cases.stream();
        }
    }

    @ParameterizedTest(name = "{0} input {index}")
    @MethodSource("references")
    void exportedFastTokenizerMatchesHuggingFaceEveryWindow(
            String name, JsonObject fixture, JsonObject expected, int window, int overlap) throws Exception {
        Path tokenizer = temp.resolve("tokenizer.json");
        Path settings = temp.resolve("tokenizer_config.json");
        Files.writeString(tokenizer, fixture.get("tokenizer").toString());
        Files.writeString(settings, fixture.get("config").toString());
        var profile = OnnxClassificationConfig.promptGuard2(tokenizer, settings, 0.5);
        var config = new OnnxClassificationConfig(tokenizer, settings, window, overlap, 2,
                profile.activation(), profile.labels());
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(config)) {
            List<Encoding> actual = windowTokenizer.encodeWindows(expected.get("text").getAsString());
            JsonArray windows = expected.getAsJsonArray("windows");
            assertThat(actual).as(name).hasSize(windows.size());
            for (int i = 0; i < windows.size(); i++) {
                JsonObject tensor = windows.get(i).getAsJsonObject();
                assertThat(actual.get(i).getIds()).as("%s window %s IDs", name, i)
                        .containsExactly(values(tensor, "input_ids"));
                assertThat(actual.get(i).getAttentionMask()).as("%s window %s attention", name, i)
                        .containsExactly(values(tensor, "attention_mask"));
                assertThat(actual.get(i).getTypeIds()).as("%s window %s type IDs", name, i)
                        .containsExactly(values(tensor, "token_type_ids"));
                assertThat(actual.get(i).getSpecialTokenMask()).as("%s window %s special tokens", name, i)
                        .containsExactly(values(tensor, "special_tokens_mask"));
            }
        }
    }

    private static long[] values(JsonObject tensor, String key) {
        JsonArray array = tensor.getAsJsonArray(key);
        long[] result = new long[array.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = array.get(i).getAsLong();
        }
        return result;
    }
}
