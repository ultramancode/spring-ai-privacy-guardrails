package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.util.JsonUtils;
import com.google.gson.JsonArray;
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

/**
 * Produces padded token windows with configurable overlap.
 * Overrides the model's saved window size and overlap with the configured values.
 */
final class TokenWindowTokenizer implements AutoCloseable {

    private final HuggingFaceTokenizer tokenizer;

    TokenWindowTokenizer(OnnxClassificationConfig onnxClassificationConfig) {
        if (!Files.isRegularFile(onnxClassificationConfig.tokenizer())
                || !Files.isRegularFile(onnxClassificationConfig.tokenizerConfig())) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
        try (Reader tokenizerConfigReader = Files.newBufferedReader(onnxClassificationConfig.tokenizerConfig());
                Reader tokenizerReader = Files.newBufferedReader(onnxClassificationConfig.tokenizer())) {
            JsonObject tokenizerConfigJson = JsonUtils.GSON.fromJson(tokenizerConfigReader, JsonObject.class);
            JsonObject tokenizerJson = JsonUtils.GSON.fromJson(tokenizerReader, JsonObject.class);
            // Each window must leave room for at least one new content token beyond the overlap.
            int specialTokenCount = countSpecialTokens(tokenizerJson.get("post_processor"));
            int contentWindowSize = onnxClassificationConfig.windowSize() - specialTokenCount;
            if (contentWindowSize <= onnxClassificationConfig.overlapSize()) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            configurePadding(tokenizerJson, tokenizerConfigJson, onnxClassificationConfig.windowSize());
            configureTruncation(tokenizerJson, tokenizerConfigJson, onnxClassificationConfig);
            // Load the adjusted JSON directly to preserve its normalization rules without DJL's Java preprocessing.
            try (InputStream input = new ByteArrayInputStream(
                    tokenizerJson.toString().getBytes(StandardCharsets.UTF_8))) {
                tokenizer = HuggingFaceTokenizer.newInstance(input,
                        Map.of("modelMaxLength", Integer.toString(onnxClassificationConfig.windowSize()),
                                "padding", "max_length", "truncation", "true",
                                "withOverflowingTokens", "true",
                                "addSpecialTokens", "true"));
            }
        } catch (IOException | RuntimeException | LinkageError ex) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
    }

    /**
     * Counts special tokens added to a single input so window overlap can be validated before loading the tokenizer.
     */
    private static int countSpecialTokens(JsonElement processor) {
        if (processor == null || processor.isJsonNull()) {
            return 0;
        }
        JsonObject processorJson = processor.getAsJsonObject();
        return switch (requireString(processorJson.get("type"))) {
            case "BertProcessing", "RobertaProcessing" -> 2;
            case "ByteLevel" -> 0;
            case "Sequence" -> {
                int specialTokenCount = 0;
                for (JsonElement childProcessor : processorJson.getAsJsonArray("processors")) {
                    specialTokenCount = Math.addExact(specialTokenCount, countSpecialTokens(childProcessor));
                }
                yield specialTokenCount;
            }
            case "TemplateProcessing" -> {
                // A "single" entry such as {"SpecialToken":{"id":"[CLS]"}} names a definition in "special_tokens".
                // Its "ids" array may contain multiple IDs, so count IDs rather than template entries.
                int specialTokenCount = 0;
                for (JsonElement entry : processorJson.getAsJsonArray("single")) {
                    JsonObject templateItem = entry.getAsJsonObject();
                    if (templateItem.has("SpecialToken")) {
                        JsonObject specialToken = templateItem.getAsJsonObject("SpecialToken");
                        String specialTokenName = requireString(specialToken.get("id"));
                        JsonObject specialTokenDefinition = processorJson.getAsJsonObject("special_tokens")
                                .getAsJsonObject(specialTokenName);
                        int tokenCount = specialTokenDefinition.getAsJsonArray("ids").size();
                        specialTokenCount = Math.addExact(specialTokenCount, tokenCount);
                    }
                }
                yield specialTokenCount;
            }
            default -> throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        };
    }

    /**
     * Configures fixed-length padding to {@code windowSize}.
     * Padding token, direction and type ID settings in tokenizer_config.json
     * take precedence over those in tokenizer.json.
     */
    private static void configurePadding(JsonObject tokenizerJson, JsonObject tokenizerConfigJson, int windowSize) {
        JsonElement paddingElement = tokenizerJson.get("padding");
        JsonObject existingPadding;
        if (paddingElement == null || paddingElement.isJsonNull()) {
            existingPadding = new JsonObject();
        } else {
            existingPadding = paddingElement.getAsJsonObject();
        }

        JsonElement configuredToken;
        if (tokenizerConfigJson.has("pad_token")) {
            configuredToken = tokenizerConfigJson.get("pad_token");
        } else {
            configuredToken = existingPadding.get("pad_token");
        }
        // pad_token may be a string or an AddedToken object with a "content" field.
        if (configuredToken != null && configuredToken.isJsonObject()) {
            configuredToken = configuredToken.getAsJsonObject().get("content");
        }
        String padToken = requireString(configuredToken);
        int padId = resolveTokenId(tokenizerJson, padToken);

        String direction;
        if (tokenizerConfigJson.has("padding_side")) {
            direction = configuredDirection(tokenizerConfigJson.get("padding_side"));
        } else {
            direction = readDirection(existingPadding);
        }
        int padTypeId = 0;
        if (tokenizerConfigJson.has("pad_token_type_id")) {
            padTypeId = requireNonNegativeInt(tokenizerConfigJson.get("pad_token_type_id"));
        } else if (existingPadding.has("pad_type_id")) {
            padTypeId = requireNonNegativeInt(existingPadding.get("pad_type_id"));
        }

        // Fixed padding with no padding multiple keeps every window exactly windowSize tokens long.
        JsonObject padding = new JsonObject();
        JsonObject strategy = new JsonObject();
        strategy.addProperty("Fixed", windowSize);
        padding.add("strategy", strategy);
        padding.addProperty("direction", direction);
        padding.add("pad_to_multiple_of", JsonNull.INSTANCE);
        padding.addProperty("pad_token", padToken);
        padding.addProperty("pad_id", padId);
        padding.addProperty("pad_type_id", padTypeId);
        tokenizerJson.add("padding", padding);
    }

    private static void configureTruncation(
            JsonObject tokenizerJson, JsonObject tokenizerConfigJson,
            OnnxClassificationConfig onnxClassificationConfig) {
        JsonElement truncationElement = tokenizerJson.get("truncation");
        JsonObject existingTruncation;
        if (truncationElement == null || truncationElement.isJsonNull()) {
            existingTruncation = new JsonObject();
        } else {
            existingTruncation = truncationElement.getAsJsonObject();
        }

        String direction;
        if (tokenizerConfigJson.has("truncation_side")) {
            direction = configuredDirection(tokenizerConfigJson.get("truncation_side"));
        } else {
            direction = readDirection(existingTruncation);
        }

        JsonObject truncation = new JsonObject();
        truncation.addProperty("direction", direction);
        truncation.addProperty("max_length", onnxClassificationConfig.windowSize());
        truncation.addProperty("strategy", "LongestFirst");
        truncation.addProperty("stride", onnxClassificationConfig.overlapSize());
        tokenizerJson.add("truncation", truncation);
    }

    private static String configuredDirection(JsonElement value) {
        return switch (requireString(value)) {
            case "left" -> "Left";
            case "right" -> "Right";
            default -> throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        };
    }

    private static String readDirection(JsonObject json) {
        String direction = json.has("direction") ? requireString(json.get("direction")) : "Right";
        if (!direction.equals("Left") && !direction.equals("Right")) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
        return direction;
    }

    private static int resolveTokenId(JsonObject tokenizerJson, String token) {
        JsonElement addedTokens = tokenizerJson.get("added_tokens");
        if (addedTokens != null && !addedTokens.isJsonNull()) {
            for (JsonElement entry : addedTokens.getAsJsonArray()) {
                JsonObject addedToken = entry.getAsJsonObject();
                String tokenText = requireString(addedToken.get("content"));
                if (token.equals(tokenText)) {
                    return requireNonNegativeInt(addedToken.get("id"));
                }
            }
        }
        // Object vocabularies map token strings to IDs.
        // Unigram uses [token, score] entries with IDs given by position.
        JsonElement vocabulary = tokenizerJson.getAsJsonObject("model").get("vocab");
        if (vocabulary != null && vocabulary.isJsonObject()) {
            JsonObject vocabularyObject = vocabulary.getAsJsonObject();
            if (vocabularyObject.has(token)) {
                return requireNonNegativeInt(vocabularyObject.get(token));
            }
        }
        if (vocabulary != null && vocabulary.isJsonArray()) {
            JsonArray vocabularyEntries = vocabulary.getAsJsonArray();
            for (int tokenId = 0; tokenId < vocabularyEntries.size(); tokenId++) {
                JsonArray vocabularyEntry = vocabularyEntries.get(tokenId).getAsJsonArray();
                String tokenText = requireString(vocabularyEntry.get(0));
                if (token.equals(tokenText)) {
                    return tokenId;
                }
            }
        }
        throw new InspectionException(InspectionFailureCode.CONFIGURATION);
    }

    private static String requireString(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
        return value.getAsString();
    }

    private static int requireNonNegativeInt(JsonElement value) {
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
