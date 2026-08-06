package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Applies privacy transformations to validated JSON-compatible recursive values. */
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
        Object validatedTree = PrivacyValueTreeValidator.validateAndCopy(
                valueTree,
                PrivacyPhase.DETOKENIZATION
        );
        TransformationBudget budget = new TransformationBudget(PrivacyPhase.DETOKENIZATION);
        return detokenizeValidatedValue(validatedTree, context, allowedEntityTypes, budget);
    }

    Object tokenizeValueTree(Object valueTree, PrivacyContext context) {
        Object validatedTree = PrivacyValueTreeValidator.validateAndCopy(
                valueTree,
                PrivacyPhase.TOKENIZATION
        );
        TransformationBudget budget = new TransformationBudget(PrivacyPhase.TOKENIZATION);
        return tokenizeValidatedValue(validatedTree, context, budget);
    }

    Object tokenizeScalar(Object scalar, List<PiiSpan> spans, PrivacyContext context) {
        Objects.requireNonNull(scalar, "scalar must not be null");
        if (scalar instanceof String text) {
            return this.textTransformer.tokenize(text, spans, context);
        }
        if (scalar instanceof Number number && PrivacyValueTreeValidator.isSupportedNumber(number)) {
            List<ResolvedPiiSpan> resolvedSpans = this.analysisCoordinator.resolveSuppliedSpans(
                    number.toString(),
                    spans
            );
            return tokenizeNumber(number, resolvedSpans, context);
        }
        throw new IllegalArgumentException("scalar must be a JSON string or number");
    }

    private Object detokenizeValidatedValue(
            Object value,
            PrivacyContext context,
            Set<String> allowedEntityTypes,
            TransformationBudget budget
    ) {
        if (value instanceof String text) {
            Object originalValue = context.originalValueTreeValueForToken(text, allowedEntityTypes);
            Object transformed = originalValue != null
                    ? originalValue
                    : this.textTransformer.detokenize(text, context, allowedEntityTypes);
            budget.acceptOutput(transformed);
            return transformed;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> transformedMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = (String) entry.getKey();
                String transformedKey = this.textTransformer.detokenize(
                        key,
                        context,
                        allowedEntityTypes
                );
                budget.acceptOutput(transformedKey);
                putTransformedEntry(
                        transformedMap,
                        transformedKey,
                        detokenizeValidatedValue(
                                entry.getValue(),
                                context,
                                allowedEntityTypes,
                                budget
                        ),
                        PrivacyPhase.DETOKENIZATION
                );
            }
            return transformedMap;
        }
        if (value instanceof List<?> list) {
            List<Object> transformedList = new ArrayList<>(list.size());
            for (Object element : list) {
                transformedList.add(detokenizeValidatedValue(
                        element,
                        context,
                        allowedEntityTypes,
                        budget
                ));
            }
            return Collections.unmodifiableList(transformedList);
        }
        budget.acceptOutput(value);
        return value;
    }

    private Object tokenizeValidatedValue(
            Object value,
            PrivacyContext context,
            TransformationBudget budget
    ) {
        if (value instanceof String text) {
            return tokenizeText(text, context, budget);
        }
        if (value instanceof Number number) {
            Object transformed = tokenizeNumber(number, context, budget);
            budget.acceptOutput(transformed);
            return transformed;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> transformedMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = (String) entry.getKey();
                String transformedKey = tokenizeText(key, context, budget);
                putTransformedEntry(
                        transformedMap,
                        transformedKey,
                        tokenizeValidatedValue(entry.getValue(), context, budget),
                        PrivacyPhase.TOKENIZATION
                );
            }
            return transformedMap;
        }
        if (value instanceof List<?> list) {
            List<Object> transformedList = new ArrayList<>(list.size());
            for (Object element : list) {
                transformedList.add(tokenizeValidatedValue(element, context, budget));
            }
            return Collections.unmodifiableList(transformedList);
        }
        budget.acceptOutput(value);
        return value;
    }

    private String tokenizeText(
            String text,
            PrivacyContext context,
            TransformationBudget budget
    ) {
        PiiAnalysisResult analysis = this.analysisCoordinator.analyzeDetailed(text);
        budget.acceptAnalysis(analysis);
        String transformed = this.textTransformer.tokenizeResolved(text, analysis.spans(), context);
        budget.acceptOutput(transformed);
        return transformed;
    }

    private Object tokenizeNumber(
            Number number,
            PrivacyContext context,
            TransformationBudget budget
    ) {
        String text = number.toString();
        PiiAnalysisResult analysis = this.analysisCoordinator.analyzeDetailed(text);
        budget.acceptAnalysis(analysis);
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

    private static void putTransformedEntry(
            Map<String, Object> target,
            String key,
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

    private static final class TransformationBudget {

        private final PrivacyPhase phase;
        private int resolvedSpanCount;
        private long outputCharacters;

        private TransformationBudget(PrivacyPhase phase) {
            this.phase = phase;
        }

        private void acceptAnalysis(PiiAnalysisResult analysis) {
            long updatedCount = (long) this.resolvedSpanCount + analysis.spans().size();
            if (updatedCount > PiiAnalyzer.MAX_RESULT_SPANS) {
                throw limitExceeded("Value tree analysis exceeded the bounded result limit");
            }
            this.resolvedSpanCount = (int) updatedCount;
        }

        private void acceptOutput(Object value) {
            if (!(value instanceof String text)) {
                return;
            }
            this.outputCharacters += text.length();
            if (this.outputCharacters > PrivacyService.MAX_TRANSFORMED_TEXT_CHARACTERS) {
                throw limitExceeded("Value tree transformation exceeded the bounded output limit");
            }
        }

        private PrivacyGuardrailException limitExceeded(String message) {
            return new PrivacyGuardrailException(
                    PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                    this.phase,
                    message
            );
        }
    }
}
