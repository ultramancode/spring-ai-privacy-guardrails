package io.github.ultramancode.springai.privacy.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Applies privacy transformations to JSON-compatible recursive values. */
final class PrivacyValueTreeTransformer {

    private final PiiAnalysisCoordinator analysisCoordinator;
    private final PrivacyTextTransformer textTransformer;
    private final String typeConflictFallback;

    PrivacyValueTreeTransformer(
            PiiAnalysisCoordinator analysisCoordinator,
            PrivacyTextTransformer textTransformer,
            String typeConflictFallback
    ) {
        this.analysisCoordinator = analysisCoordinator;
        this.textTransformer = textTransformer;
        this.typeConflictFallback = Objects.requireNonNull(
                typeConflictFallback,
                "typeConflictFallback must not be null"
        );
    }

    Set<String> requireValidEntityTypes(Set<String> entityTypes) {
        Set<String> canonicalTypes = new LinkedHashSet<>();
        for (String entityType : Objects.requireNonNull(
                entityTypes,
                "allowedEntityTypes must not be null"
        )) {
            canonicalTypes.add(EntityTypeRegistry.requireValidEntityType(entityType));
        }
        return Set.copyOf(canonicalTypes);
    }

    Object detokenizeValueTree(
            Object valueTree,
            PrivacyContext context,
            Set<String> allowedEntityTypes
    ) {
        if (valueTree instanceof String text) {
            Object originalValue = context.originalValueTreeValueForToken(text, allowedEntityTypes);
            if (originalValue != null) {
                return originalValue;
            }
            return this.textTransformer.detokenize(text, context, allowedEntityTypes);
        }
        if (valueTree instanceof Map<?, ?> map) {
            Map<Object, Object> transformedMap = new LinkedHashMap<>();
            map.forEach((key, value) -> putTransformedEntry(
                    transformedMap,
                    key instanceof String text
                            ? detokenizeValueTree(text, context, allowedEntityTypes) : key,
                    detokenizeValueTree(value, context, allowedEntityTypes),
                    PrivacyPhase.DETOKENIZATION
            ));
            return transformedMap;
        }
        if (valueTree instanceof List<?> list) {
            return list.stream()
                    .map(value -> detokenizeValueTree(value, context, allowedEntityTypes))
                    .toList();
        }
        return valueTree;
    }

    Object tokenizeValueTree(Object valueTree, PrivacyContext context) {
        if (valueTree instanceof String text) {
            return this.textTransformer.tokenize(text, context);
        }
        if (valueTree instanceof Number number && isJsonNumber(number)) {
            return tokenizeNumber(number, context);
        }
        if (valueTree instanceof Map<?, ?> map) {
            Map<Object, Object> transformedMap = new LinkedHashMap<>();
            map.forEach((key, value) -> putTransformedEntry(
                    transformedMap,
                    key instanceof String text ? this.textTransformer.tokenize(text, context) : key,
                    tokenizeValueTree(value, context),
                    PrivacyPhase.TOKENIZATION
            ));
            return transformedMap;
        }
        if (valueTree instanceof List<?> list) {
            return list.stream().map(value -> tokenizeValueTree(value, context)).toList();
        }
        return valueTree;
    }

    Object tokenizeScalar(Object scalar, List<PiiSpan> spans, PrivacyContext context) {
        Objects.requireNonNull(scalar, "scalar must not be null");
        if (scalar instanceof String text) {
            return this.textTransformer.tokenize(text, spans, context);
        }
        if (scalar instanceof Number number && isJsonNumber(number)) {
            List<ResolvedPiiSpan> resolvedSpans = this.analysisCoordinator.resolveSuppliedSpans(
                    number.toString(),
                    spans
            );
            return tokenizeNumber(number, resolvedSpans, context);
        }
        throw new IllegalArgumentException("scalar must be a JSON string or number");
    }

    private Object tokenizeNumber(Number number, PrivacyContext context) {
        String text = number.toString();
        PiiAnalysisResult analysis = this.analysisCoordinator.analyzeDetailed(text);
        return tokenizeNumber(number, analysis.spans(), context);
    }

    private Object tokenizeNumber(
            Number number,
            List<ResolvedPiiSpan> spans,
            PrivacyContext context
    ) {
        if (spans.isEmpty()) {
            return number;
        }
        Set<String> entityTypes = spans.stream()
                .map(ResolvedPiiSpan::entityType)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        String entityType = entityTypes.size() == 1
                ? entityTypes.iterator().next()
                : this.typeConflictFallback;
        return context.tokenForNumber(entityType, number);
    }

    private static boolean isJsonNumber(Number number) {
        if (number instanceof Double value) {
            return Double.isFinite(value);
        }
        if (number instanceof Float value) {
            return Float.isFinite(value);
        }
        return number instanceof Byte
                || number instanceof Short
                || number instanceof Integer
                || number instanceof Long
                || number instanceof BigInteger
                || number instanceof BigDecimal;
    }

    private static void putTransformedEntry(
            Map<Object, Object> target,
            Object key,
            Object value,
            PrivacyPhase phase
    ) {
        if (target.containsKey(key)) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    phase,
                    "PII transformation produced duplicate map keys"
            );
        }
        target.put(key, value);
    }
}
