package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Protects provider-defined metadata fields that are known to contain model-visible or
 * application-visible reasoning text. Opaque signatures and unknown typed metadata are
 * deliberately preserved rather than recursively rewritten.
 */
final class PrivacyProviderTextMetadataTransformer {

    private static final List<String> TEXT_METADATA_KEYS = List.of(
            "reasoningContent",
            "thinking"
    );

    private PrivacyProviderTextMetadataTransformer() {
    }

    static Map<String, Object> transformMessageMetadata(
            Map<String, Object> metadata,
            UnaryOperator<String> textTransformer
    ) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Objects.requireNonNull(textTransformer, "textTransformer must not be null");
        Map<String, Object> transformed = metadata;
        for (String key : TEXT_METADATA_KEYS) {
            Object value = metadata.get(key);
            if (!(value instanceof String text)) {
                continue;
            }
            String protectedText = textTransformer.apply(text);
            if (!Objects.equals(protectedText, text)) {
                if (transformed == metadata) {
                    transformed = new LinkedHashMap<>(metadata);
                }
                transformed.put(key, protectedText);
            }
        }
        return transformed;
    }

    static Map<String, Object> aggregateMessageMetadata(
            List<Map<String, Object>> metadataFrames,
            Map<String, Object> terminalMetadata
    ) {
        return aggregateMetadata(metadataFrames, terminalMetadata);
    }

    static Map<String, Object> clearMessageTextMetadata(Map<String, Object> metadata) {
        return clearTextMetadata(metadata);
    }

    static long textCharacterCount(Map<String, Object> metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        long characters = 0;
        for (String key : TEXT_METADATA_KEYS) {
            Object value = metadata.get(key);
            if (value instanceof String text) {
                characters = safeAdd(characters, text.length());
            }
        }
        return characters;
    }

    static ChatGenerationMetadata transformGenerationMetadata(
            ChatGenerationMetadata metadata,
            UnaryOperator<String> textTransformer
    ) {
        if (metadata == null) {
            return null;
        }
        Map<String, Object> properties = generationProperties(metadata);
        Map<String, Object> transformed = transformMessageMetadata(properties, textTransformer);
        return transformed == properties ? metadata : copyGenerationMetadata(metadata, transformed);
    }

    static ChatGenerationMetadata aggregateGenerationMetadata(
            List<ChatGenerationMetadata> metadataFrames,
            ChatGenerationMetadata terminalMetadata
    ) {
        Objects.requireNonNull(metadataFrames, "metadataFrames must not be null");
        List<Map<String, Object>> propertyFrames = new ArrayList<>(metadataFrames.size());
        for (ChatGenerationMetadata metadata : metadataFrames) {
            propertyFrames.add(metadata == null ? Map.of() : generationProperties(metadata));
        }
        Map<String, Object> terminalProperties = terminalMetadata == null
                ? Map.of()
                : generationProperties(terminalMetadata);
        Map<String, Object> aggregated = aggregateMetadata(propertyFrames, terminalProperties);
        if (aggregated == terminalProperties) {
            return terminalMetadata;
        }
        return copyGenerationMetadata(terminalMetadata, aggregated);
    }

    static ChatGenerationMetadata clearGenerationTextMetadata(ChatGenerationMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        Map<String, Object> properties = generationProperties(metadata);
        Map<String, Object> cleared = clearTextMetadata(properties);
        return cleared == properties ? metadata : copyGenerationMetadata(metadata, cleared);
    }

    static long textCharacterCount(ChatGenerationMetadata metadata) {
        return metadata == null ? 0 : textCharacterCount(generationProperties(metadata));
    }

    private static Map<String, Object> aggregateMetadata(
            List<Map<String, Object>> metadataFrames,
            Map<String, Object> terminalMetadata
    ) {
        Objects.requireNonNull(metadataFrames, "metadataFrames must not be null");
        Objects.requireNonNull(terminalMetadata, "terminalMetadata must not be null");
        Map<String, Object> aggregated = terminalMetadata;
        for (String key : TEXT_METADATA_KEYS) {
            String merged = null;
            boolean stringValueSeen = false;
            boolean nonStringValueSeen = false;
            for (Map<String, Object> frame : metadataFrames) {
                if (!frame.containsKey(key) || frame.get(key) == null) {
                    continue;
                }
                Object value = frame.get(key);
                if (value instanceof String text) {
                    if (nonStringValueSeen) {
                        throw metadataCorrelationFailure();
                    }
                    merged = mergeFragment(merged, text);
                    stringValueSeen = true;
                } else {
                    if (stringValueSeen) {
                        throw metadataCorrelationFailure();
                    }
                    nonStringValueSeen = true;
                }
            }
            if (stringValueSeen && !Objects.equals(merged, terminalMetadata.get(key))) {
                if (aggregated == terminalMetadata) {
                    aggregated = new LinkedHashMap<>(terminalMetadata);
                }
                aggregated.put(key, merged);
            }
        }
        return aggregated;
    }

    private static Map<String, Object> clearTextMetadata(Map<String, Object> metadata) {
        Objects.requireNonNull(metadata, "metadata must not be null");
        Map<String, Object> cleared = metadata;
        for (String key : TEXT_METADATA_KEYS) {
            if (!(metadata.get(key) instanceof String text) || text.isEmpty()) {
                continue;
            }
            if (cleared == metadata) {
                cleared = new LinkedHashMap<>(metadata);
            }
            cleared.put(key, "");
        }
        return cleared;
    }

    private static Map<String, Object> generationProperties(ChatGenerationMetadata metadata) {
        Map<String, Object> properties = new LinkedHashMap<>();
        metadata.entrySet().forEach(entry -> properties.put(entry.getKey(), entry.getValue()));
        return properties;
    }

    private static ChatGenerationMetadata copyGenerationMetadata(
            ChatGenerationMetadata source,
            Map<String, Object> properties
    ) {
        ChatGenerationMetadata.Builder builder = ChatGenerationMetadata.builder().metadata(properties);
        if (source != null) {
            builder.finishReason(source.getFinishReason()).contentFilters(source.getContentFilters());
        }
        return builder.build();
    }

    private static String mergeFragment(String previous, String current) {
        // Providers may send the full text-so-far or only the new fragment; avoid duplicate prefixes.
        if (previous == null || previous.isEmpty()) {
            return current;
        }
        if (current == null || current.isEmpty() || previous.equals(current) || previous.startsWith(current)) {
            return previous;
        }
        if (current.startsWith(previous)) {
            return current;
        }
        return previous + current;
    }

    private static long safeAdd(long current, long additional) {
        if (additional < 0 || current > Long.MAX_VALUE - additional) {
            return Long.MAX_VALUE;
        }
        return current + additional;
    }

    private static PrivacyGuardrailException metadataCorrelationFailure() {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                PrivacyPhase.OUTPUT_POLICY,
                "Provider text metadata cannot be correlated safely"
        );
    }
}
