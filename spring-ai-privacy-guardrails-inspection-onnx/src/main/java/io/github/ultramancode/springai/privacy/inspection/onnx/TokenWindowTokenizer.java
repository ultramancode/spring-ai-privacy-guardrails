package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.util.JsonUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Produces padded, overlapping token windows from matching Tokenizers JSON files supported by DJL. */
final class TokenWindowTokenizer implements AutoCloseable {

    private final HuggingFaceTokenizer tokenizer;

    TokenWindowTokenizer(OnnxClassificationConfig config) {
        if (!Files.isRegularFile(config.tokenizer()) || !Files.isRegularFile(config.tokenizerConfig())) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
        try (Reader reader = Files.newBufferedReader(config.tokenizerConfig());
                Reader tokenizerReader = Files.newBufferedReader(config.tokenizer())) {
            JsonObject settings = JsonUtils.GSON.fromJson(reader, JsonObject.class);
            JsonObject definition = JsonUtils.GSON.fromJson(tokenizerReader, JsonObject.class);
            int contentTokens = config.maxTokens() - addedSpecialTokens(definition.get("post_processor"));
            if (contentTokens <= config.overlapTokens()) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            configurePadding(definition, settings, config.maxTokens());
            configureTruncation(definition, settings, config);
            // The serialized graph owns normalization, pre-tokenization and added tokens.
            // DJL's config-aware factory adds Java preprocessing with different semantics.
            try (InputStream input = new ByteArrayInputStream(
                    definition.toString().getBytes(StandardCharsets.UTF_8))) {
                // DJL reads maxLength, stride and padToMultipleOf from the configured graph.
                tokenizer = HuggingFaceTokenizer.newInstance(input,
                        Map.of("modelMaxLength", Integer.toString(config.maxTokens()),
                                "padding", "max_length", "truncation", "true",
                                "withOverflowingTokens", "true",
                                "addSpecialTokens", "true"));
            }
        } catch (IOException | RuntimeException | LinkageError ex) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
    }

    /** Mirrors Hugging Face PostProcessor.added_tokens(false) before native truncation is enabled. */
    private static int addedSpecialTokens(JsonElement processor) {
        if (processor == null || processor.isJsonNull()) {
            return 0;
        }
        JsonObject definition = processor.getAsJsonObject();
        return switch (string(definition.get("type"))) {
            case "BertProcessing", "RobertaProcessing" -> 2;
            case "ByteLevel" -> 0;
            case "Sequence" -> {
                int count = 0;
                for (JsonElement child : definition.getAsJsonArray("processors")) {
                    count = Math.addExact(count, addedSpecialTokens(child));
                }
                yield count;
            }
            case "TemplateProcessing" -> {
                int count = 0;
                for (JsonElement entry : definition.getAsJsonArray("single")) {
                    JsonObject piece = entry.getAsJsonObject();
                    if (piece.has("SpecialToken")) {
                        String id = string(piece.getAsJsonObject("SpecialToken").get("id"));
                        int width = definition.getAsJsonObject("special_tokens")
                                .getAsJsonObject(id).getAsJsonArray("ids").size();
                        count = Math.addExact(count, width);
                    }
                }
                yield count;
            }
            default -> throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        };
    }

    private static void configurePadding(JsonObject definition, JsonObject settings, int maxTokens) {
        JsonElement savedPadding = definition.get("padding");
        JsonObject saved = savedPadding == null || savedPadding.isJsonNull()
                ? new JsonObject() : savedPadding.getAsJsonObject();
        JsonElement configuredToken = settings.has("pad_token") ? settings.get("pad_token") : saved.get("pad_token");
        if (configuredToken != null && configuredToken.isJsonObject()) {
            configuredToken = configuredToken.getAsJsonObject().get("content");
        }
        String padToken = string(configuredToken);
        int padId = resolveTokenId(definition, padToken);
        String direction = settings.has("padding_side")
                ? configuredDirection(settings.get("padding_side")) : savedDirection(saved);
        int typeId = 0;
        if (settings.has("pad_token_type_id")) {
            typeId = nonnegativeInt(settings.get("pad_token_type_id"));
        } else if (saved.has("pad_type_id")) {
            typeId = nonnegativeInt(saved.get("pad_type_id"));
        }
        JsonObject padding = new JsonObject();
        JsonObject strategy = new JsonObject();
        strategy.addProperty("Fixed", maxTokens);
        padding.add("strategy", strategy);
        padding.addProperty("direction", direction);
        padding.add("pad_to_multiple_of", JsonNull.INSTANCE);
        padding.addProperty("pad_token", padToken);
        padding.addProperty("pad_id", padId);
        padding.addProperty("pad_type_id", typeId);
        definition.add("padding", padding);
    }

    private static void configureTruncation(
            JsonObject definition, JsonObject settings, OnnxClassificationConfig config) {
        JsonElement savedTruncation = definition.get("truncation");
        JsonObject saved = savedTruncation == null || savedTruncation.isJsonNull()
                ? new JsonObject() : savedTruncation.getAsJsonObject();
        JsonObject truncation = new JsonObject();
        truncation.addProperty("direction", settings.has("truncation_side")
                ? configuredDirection(settings.get("truncation_side")) : savedDirection(saved));
        truncation.addProperty("max_length", config.maxTokens());
        truncation.addProperty("strategy", "LongestFirst");
        truncation.addProperty("stride", config.overlapTokens());
        definition.add("truncation", truncation);
    }

    private static String configuredDirection(JsonElement value) {
        return switch (string(value)) {
            case "left" -> "Left";
            case "right" -> "Right";
            default -> throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        };
    }

    private static String savedDirection(JsonObject settings) {
        String direction = settings.has("direction") ? string(settings.get("direction")) : "Right";
        if (!direction.equals("Left") && !direction.equals("Right")) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
        return direction;
    }

    private static int resolveTokenId(JsonObject definition, String token) {
        JsonElement added = definition.get("added_tokens");
        if (added != null && !added.isJsonNull()) {
            for (JsonElement entry : added.getAsJsonArray()) {
                JsonObject item = entry.getAsJsonObject();
                if (token.equals(string(item.get("content")))) {
                    return nonnegativeInt(item.get("id"));
                }
            }
        }
        JsonElement vocab = definition.getAsJsonObject("model").get("vocab");
        if (vocab != null && vocab.isJsonObject() && vocab.getAsJsonObject().has(token)) {
            return nonnegativeInt(vocab.getAsJsonObject().get(token));
        }
        if (vocab != null && vocab.isJsonArray()) {
            for (int i = 0; i < vocab.getAsJsonArray().size(); i++) {
                if (token.equals(string(vocab.getAsJsonArray().get(i).getAsJsonArray().get(0)))) {
                    return i;
                }
            }
        }
        throw new InspectionException(InspectionFailureCode.CONFIGURATION);
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
        return value.getAsString();
    }

    private static int nonnegativeInt(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
        int number = value.getAsBigDecimal().intValueExact();
        if (number < 0) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
        return number;
    }

    List<Encoding> encodeWindows(String text) {
        Encoding first = tokenizer.encode(text);
        Encoding[] overflow = first.getOverflowing();
        List<Encoding> windows = new ArrayList<>(1 + overflow.length);
        windows.add(first);
        windows.addAll(List.of(overflow));
        return windows;
    }

    @Override
    public void close() {
        tokenizer.close();
    }
}
