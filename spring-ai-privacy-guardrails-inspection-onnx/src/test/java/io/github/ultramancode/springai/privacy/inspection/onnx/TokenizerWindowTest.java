package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.util.JsonUtils;
import com.google.gson.JsonObject;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenizerWindowTest {

    @TempDir Path temp;

    private static Stream<Arguments> references(String casesKey) throws Exception {
        try (var reader = new InputStreamReader(TokenizerWindowTest.class.getResourceAsStream(
                "/tokenizer-window-reference.json"), StandardCharsets.UTF_8)) {
            JsonObject reference = JsonUtils.GSON.fromJson(reader, JsonObject.class);
            List<Arguments> cases = new ArrayList<>();
            for (var fixture : reference.getAsJsonArray("fixtures")) {
                JsonObject item = fixture.getAsJsonObject();
                for (var entry : item.getAsJsonArray(casesKey)) {
                    cases.add(Arguments.of(item.get("name").getAsString(), item, entry.getAsJsonObject()));
                }
            }
            return cases.stream();
        }
    }

    static Stream<Arguments> invalidWindows() throws Exception {
        return references("invalidWindows");
    }

    static Stream<Arguments> validWindows() throws Exception {
        return references("cases");
    }

    private OnnxClassificationConfig config(JsonObject fixture, JsonObject window) throws Exception {
        Path tokenizer = temp.resolve("tokenizer.json");
        Path settings = temp.resolve("tokenizer_config.json");
        Files.writeString(tokenizer, fixture.get("tokenizer").toString());
        JsonObject configuration = new JsonObject();
        configuration.addProperty("pad_token", "[PAD]");
        if (window.has("direction")) {
            configuration.add("padding_side", window.get("direction"));
            configuration.add("truncation_side", window.get("direction"));
        }
        Files.writeString(settings, configuration.toString());
        var profile = OnnxClassificationConfig.promptGuard2(tokenizer, settings, 0.5);
        return new OnnxClassificationConfig(tokenizer, settings,
                window.get("maxTokens").getAsInt(), window.get("overlapTokens").getAsInt(),
                2, profile.activation(), profile.labels());
    }

    @ParameterizedTest(name = "{0} invalid window {index}")
    @MethodSource("invalidWindows")
    void rejectsUnsafeWindowsDuringInitialization(String name, JsonObject fixture, JsonObject window) throws Exception {
        OnnxClassificationConfig config = config(fixture, window);
        assertThatThrownBy(() -> {
            try (TokenWindowTokenizer ignored = new TokenWindowTokenizer(config)) {
                // Initialization must fail without waiting for an input that needs truncation.
            }
        }).as(name).isInstanceOfSatisfying(InspectionException.class,
                failure -> assertThat(failure.failure()).isEqualTo(InspectionFailureCode.CONFIGURATION))
                .hasNoCause();
    }

    @ParameterizedTest(name = "{0} valid window {index}")
    @MethodSource("validWindows")
    void adjacentValidWindowsMatchHuggingFace(String name, JsonObject fixture, JsonObject expected) throws Exception {
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(config(fixture, expected))) {
            List<Encoding> actual = windowTokenizer.encodeWindows(expected.get("text").getAsString());
            var windows = expected.getAsJsonArray("windows");
            assertThat(actual).as(name).hasSize(windows.size());
            for (int i = 0; i < actual.size(); i++) {
                JsonObject tensor = windows.get(i).getAsJsonObject();
                assertThat(actual.get(i).getIds()).containsExactly(values(tensor, "input_ids"));
                assertThat(actual.get(i).getAttentionMask()).containsExactly(values(tensor, "attention_mask"));
                assertThat(actual.get(i).getTypeIds()).containsExactly(values(tensor, "token_type_ids"));
                assertThat(actual.get(i).getSpecialTokenMask()).containsExactly(values(tensor, "special_tokens_mask"));
            }
        }
    }

    private static long[] values(JsonObject tensor, String key) {
        var array = tensor.getAsJsonArray(key);
        long[] values = new long[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsLong();
        }
        return values;
    }
}
