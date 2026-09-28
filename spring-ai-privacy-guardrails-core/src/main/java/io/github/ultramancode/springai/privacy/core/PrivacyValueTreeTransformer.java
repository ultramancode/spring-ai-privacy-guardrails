package io.github.ultramancode.springai.privacy.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Applies privacy transformations to validated JSON-compatible value trees. */
final class PrivacyValueTreeTransformer {

    private final PiiAnalysisCoordinator analysisCoordinator;
    private final SessionAnalysis sessionAnalysis;
    private final PrivacyTextTransformer textTransformer;
    private final String typeConflictFallback;
    private final PrivacyProcessingLimits processingLimits;

    PrivacyValueTreeTransformer(
            PiiAnalysisCoordinator analysisCoordinator,
            SessionAnalysis sessionAnalysis,
            PrivacyTextTransformer textTransformer,
            String typeConflictFallback,
            PrivacyProcessingLimits processingLimits
    ) {
        this.analysisCoordinator = analysisCoordinator;
        this.sessionAnalysis = sessionAnalysis;
        this.processingLimits = processingLimits;
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
                PrivacyPhase.DETOKENIZATION,
                this.processingLimits
        );
        TransformationBudget budget = new TransformationBudget(
                PrivacyPhase.DETOKENIZATION, this.processingLimits);
        return detokenizeValidatedValue(validatedTree, context, allowedEntityTypes, budget);
    }

    Object tokenizeValueTree(Object valueTree, PrivacyContext context) {
        Object validatedTree = PrivacyValueTreeValidator.validateAndCopy(
                valueTree,
                PrivacyPhase.TOKENIZATION,
                this.processingLimits
        );
        TransformationBudget budget = new TransformationBudget(
                PrivacyPhase.TOKENIZATION, this.processingLimits);
        Object transformed = tokenizeValidatedValue(validatedTree, context, budget);
        context.requireActive();
        budget.publishCompletions(context);
        return transformed;
    }

    Object tokenizeScalar(Object scalar, List<PiiSpan> spans, PrivacyContext context) {
        Objects.requireNonNull(scalar, "scalar must not be null");
        if (scalar instanceof String text) {
            return this.textTransformer.tokenize(text, spans, context);
        }
        if (scalar instanceof Number number && PrivacyValueTreeValidator.isSupportedNumber(number)) {
            PrivacyValueTreeValidator.validateAndCopy(
                    number, PrivacyPhase.ANALYSIS, this.processingLimits);
            List<ResolvedPiiSpan> resolvedSpans = this.analysisCoordinator.resolveSuppliedSpans(
                    analysisText(number),
                    spans
            );
            return tokenizeNumber(number, resolvedSpans, context);
        }
        throw new IllegalArgumentException("scalar must be a JSON string or number");
    }

    List<Object> tokenizeScalars(List<?> scalars, PrivacyContext context) {
        Objects.requireNonNull(scalars, "scalars must not be null");
        context.requireActive();
        List<ScalarInput> inputs = prepareScalars(scalars);
        List<String> analysisTexts = new ArrayList<>();
        // Keep existing completions for this operation even if the session cache evicts them.
        Set<String> existingCompletedInputs = new LinkedHashSet<>();
        for (ScalarInput input : inputs) {
            String text = input.analysisText();
            if (input.value() instanceof String
                    && (existingCompletedInputs.contains(text) || context.isCompletedTokenization(text))) {
                existingCompletedInputs.add(text);
                continue;
            }
            // Keep duplicates so evidence limits count every input, even when analysis is reused.
            analysisTexts.add(text);
        }
        List<PiiAnalysisCoordinator.AnalysisEvidence> analysisResults =
                this.sessionAnalysis.analyzeSegmentsEvidence(analysisTexts, context);
        Map<String, PiiAnalysisCoordinator.AnalysisEvidence> evidenceByText = new LinkedHashMap<>();
        for (int index = 0; index < analysisTexts.size(); index++) {
            evidenceByText.put(analysisTexts.get(index), analysisResults.get(index));
        }
        List<Object> transformedScalars = new ArrayList<>(inputs.size());
        // Publish newly completed outputs only after the entire scalar transformation succeeds.
        List<String> newlyCompletedOutputs = new ArrayList<>();
        long outputCharacters = 0;
        for (ScalarInput input : inputs) {
            Object scalar = input.value();
            Object value;
            if (scalar instanceof String text && existingCompletedInputs.contains(text)) {
                value = text;
            } else {
                PiiAnalysisCoordinator.AnalysisEvidence evidence =
                        evidenceByText.get(input.analysisText());
                value = scalar instanceof String text
                        ? this.textTransformer.tokenizeWithResolvedSpans(text, evidence.result().spans(), context)
                        : tokenizeNumber((Number) scalar, evidence.result().spans(), context);
                if (value instanceof String protectedText && evidence.result().failures().isEmpty()) {
                    newlyCompletedOutputs.add(protectedText);
                }
            }
            if (value instanceof String text) {
                outputCharacters += text.length();
                requireWithinLimit(outputCharacters, this.processingLimits.maxOutputCharacters(),
                        PrivacyPhase.TOKENIZATION);
            }
            transformedScalars.add(value);
        }
        context.requireActive();
        for (String newlyCompletedOutput : newlyCompletedOutputs) {
            context.retainCompletedTokenization(newlyCompletedOutput);
        }
        return Collections.unmodifiableList(transformedScalars);
    }

    private List<ScalarInput> prepareScalars(List<?> scalars) {
        int scalarCount = scalars.size();
        requireWithinLimit(scalarCount, this.processingLimits.maxValueTreeNodes(),
                PrivacyPhase.ANALYSIS);
        List<ScalarInput> inputs = new ArrayList<>(scalarCount);
        long inputCharacters = 0;
        for (Object scalar : scalars) {
            if (!(scalar instanceof String) && !(scalar instanceof Number number
                    && PrivacyValueTreeValidator.isSupportedNumber(number))) {
                throw new IllegalArgumentException("scalars must contain only JSON strings and numbers");
            }
            Object validatedScalar = PrivacyValueTreeValidator.validateAndCopy(
                    scalar, PrivacyPhase.ANALYSIS, this.processingLimits);
            String analysisText = analysisText(validatedScalar);
            inputCharacters += analysisText.length();
            requireWithinLimit(inputCharacters, this.processingLimits.maxTextCharacters(),
                    PrivacyPhase.ANALYSIS);
            inputs.add(new ScalarInput(validatedScalar, analysisText));
        }
        return inputs;
    }

    private record ScalarInput(Object value, String analysisText) {
    }

    private String analysisText(Object scalar) {
        if (scalar instanceof BigDecimal decimal) {
            requireWithinLimit(ScalarAnalysisText.plainDecimalLength(decimal),
                    Math.min(this.processingLimits.maxTextCharacters(),
                            this.processingLimits.maxValueTreeCharacters()),
                    PrivacyPhase.ANALYSIS);
        }
        return ScalarAnalysisText.toAnalysisText(scalar);
    }

    private static void requireWithinLimit(long actual, int maximum, PrivacyPhase phase) {
        if (actual > maximum) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED, phase,
                    "Privacy scalar processing exceeded a configured limit");
        }
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
        if (this.textTransformer.canReuseCompletedTokenization(text, context)) {
            budget.acceptOutput(text);
            return text;
        }
        PiiAnalysisCoordinator.AnalysisEvidence evidence =
                this.sessionAnalysis.analyzeEvidence(text, context);
        budget.acceptAnalysis(evidence.evidenceCount());
        String transformed = this.textTransformer.tokenizeWithResolvedSpans(
                text, evidence.result().spans(), context);
        budget.acceptOutput(transformed);
        if (evidence.result().failures().isEmpty()) {
            budget.recordCompletion(transformed);
        }
        return transformed;
    }

    private Object tokenizeNumber(
            Number number,
            PrivacyContext context,
            TransformationBudget budget
    ) {
        String text = analysisText(number);
        PiiAnalysisCoordinator.AnalysisEvidence evidence =
                this.sessionAnalysis.analyzeEvidence(text, context);
        budget.acceptAnalysis(evidence.evidenceCount());
        Object transformed = tokenizeNumber(number, evidence.result().spans(), context);
        if (transformed instanceof String protectedText && evidence.result().failures().isEmpty()) {
            budget.recordCompletion(protectedText);
        }
        return transformed;
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
        this.textTransformer.requireOutputLength(
                OpaquePiiTokenFormat.minimumGeneratedTokenLength(entityType), PrivacyPhase.TOKENIZATION);
        String token = context.tokenForNumber(entityType, number);
        this.textTransformer.requireOutputLength(token.length(), PrivacyPhase.TOKENIZATION);
        return token;
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
        private final PrivacyProcessingLimits limits;
        private int evidenceCount;
        private long outputCharacters;
        private final List<String> completedOutputs = new ArrayList<>();

        private TransformationBudget(PrivacyPhase phase, PrivacyProcessingLimits limits) {
            this.phase = phase;
            this.limits = limits;
        }

        private void acceptAnalysis(int additionalEvidence) {
            long updatedCount = (long) this.evidenceCount + additionalEvidence;
            if (updatedCount > this.limits.maxResultSpans()) {
                throw limitExceeded("Value tree analyzer span limit exceeded");
            }
            this.evidenceCount = (int) updatedCount;
        }

        private void acceptOutput(Object value) {
            if (!(value instanceof String text)) {
                return;
            }
            this.outputCharacters += text.length();
            if (this.outputCharacters > this.limits.maxOutputCharacters()) {
                throw limitExceeded("Value tree transformed output limit exceeded");
            }
        }

        private void recordCompletion(String text) {
            this.completedOutputs.add(text);
        }

        private void publishCompletions(PrivacyContext context) {
            for (String text : this.completedOutputs) {
                context.retainCompletedTokenization(text);
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
