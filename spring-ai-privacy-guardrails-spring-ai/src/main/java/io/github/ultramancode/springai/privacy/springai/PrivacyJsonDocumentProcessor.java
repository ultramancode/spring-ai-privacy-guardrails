package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import io.github.ultramancode.springai.privacy.core.ScalarAnalysisText;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.ObjectWriteContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamWriteConstraints;
import tools.jackson.core.json.JsonFactory;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Validates and rewrites bounded JSON while preserving untouched numeric lexemes. */
final class PrivacyJsonDocumentProcessor {

    private final PrivacyProcessingLimits limits;
    private final JsonFactory jsonFactory;

    PrivacyJsonDocumentProcessor(PrivacyProcessingLimits limits) {
        this.limits = limits;
        // Each container contributes two tokens (start and end), but counts as one node.
        long maxJsonTokens = 2L * limits.maxValueTreeNodes();
        this.jsonFactory = JsonFactory.builder()
                .streamWriteConstraints(StreamWriteConstraints.builder()
                        .maxNestingDepth(limits.maxDepth())
                        .build())
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(limits.maxTextCharacters())
                        .maxStringLength(limits.maxValueTreeCharacters())
                        .maxNameLength(limits.maxValueTreeCharacters())
                        .maxNumberLength(limits.maxValueTreeCharacters())
                        .maxNestingDepth(limits.maxDepth())
                        .maxTokenCount(maxJsonTokens)
                        .build())
                .build();
    }

    String transform(
            String payload,
            Function<Object, Object> scalarTransformer,
            PrivacyPhase phase
    ) throws JacksonException {
        validateAndCollectAnalysisTexts(payload, phase);
        return rewrite(payload, scalarTransformer, phase);
    }

    List<String> validateAndCollectAnalysisTexts(
            String payload,
            PrivacyPhase phase
    ) throws JacksonException {
        return validateAndCollect(payload, phase).analysisTexts();
    }

    List<Object> validateAndCollectScalars(String payload, PrivacyPhase phase)
            throws JacksonException {
        return validateAndCollect(payload, phase).scalars();
    }

    private CollectedScalarInputs validateAndCollect(String payload, PrivacyPhase phase)
            throws JacksonException {
        validateSingleJsonValue(payload);
        Set<String> uniqueAnalysisTexts = new LinkedHashSet<>();
        Set<Object> uniqueScalars = new LinkedHashSet<>();
        ProcessingBudget budget = new ProcessingBudget(phase, this.limits);
        long analysisCharacters = 0L;
        try (JsonParser parser = this.jsonFactory.createParser(ObjectReadContext.empty(), payload)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token != JsonToken.END_OBJECT && token != JsonToken.END_ARRAY) {
                    budget.acceptNode();
                }
                if (token == JsonToken.PROPERTY_NAME || token == JsonToken.VALUE_STRING) {
                    String scalar = parser.getString();
                    uniqueScalars.add(scalar);
                    budget.acceptInputCharacters(scalar.length());
                    if (!scalar.isBlank() && uniqueAnalysisTexts.add(scalar)) {
                        analysisCharacters += scalar.length();
                        PrivacyJsonPayloadTransformer.requireWithinLimit(
                                analysisCharacters, this.limits.maxTextCharacters(), phase);
                    }
                } else if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                    String lexeme = parser.getString();
                    budget.acceptInputCharacters(lexeme.length());
                    Number number = losslessNumber(lexeme, token, phase, this.limits);
                    uniqueScalars.add(number);
                    String analysisText = ScalarAnalysisText.toAnalysisText(number);
                    if (uniqueAnalysisTexts.add(analysisText)) {
                        analysisCharacters += analysisText.length();
                        PrivacyJsonPayloadTransformer.requireWithinLimit(
                                analysisCharacters, this.limits.maxTextCharacters(), phase);
                    }
                }
            }
        }
        return new CollectedScalarInputs(
                List.copyOf(uniqueAnalysisTexts), List.copyOf(uniqueScalars));
    }

    private record CollectedScalarInputs(List<String> analysisTexts, List<Object> scalars) {
    }

    String rewrite(
            String payload,
            Function<Object, Object> scalarTransformer,
            PrivacyPhase phase
    ) throws JacksonException {
        PrivacyJsonBoundedWriter writer = new PrivacyJsonBoundedWriter(
                payload.length(), this.limits.maxOutputCharacters());
        ProcessingBudget budget = new ProcessingBudget(phase, this.limits);
        Map<Object, Object> transformedScalars = new HashMap<>();
        Function<Object, Object> memoizedTransformer = scalar -> {
            if (transformedScalars.containsKey(scalar)) {
                return transformedScalars.get(scalar);
            }
            Object transformed = scalarTransformer.apply(scalar);
            transformedScalars.put(scalar, transformed);
            return transformed;
        };
        try (JsonParser parser = this.jsonFactory.createParser(ObjectReadContext.empty(), payload);
             JsonGenerator generator = this.jsonFactory.createGenerator(ObjectWriteContext.empty(), writer)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new InvalidJsonPayload();
            }
            writeValue(parser, generator, first, memoizedTransformer, phase, budget);
            if (parser.nextToken() != null) {
                throw new InvalidJsonPayload();
            }
        }
        return PrivacyJsonPayloadTransformer.requireTransformedResult(writer.toString(), phase, this.limits);
    }

    private void validateSingleJsonValue(String payload) throws JacksonException {
        try (JsonParser parser = this.jsonFactory.createParser(ObjectReadContext.empty(), payload)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new InvalidJsonPayload();
            }
            parser.skipChildren();
            if (parser.nextToken() != null) {
                throw new InvalidJsonPayload();
            }
        }
    }

    private static void writeValue(
            JsonParser parser,
            JsonGenerator generator,
            JsonToken token,
            Function<Object, Object> scalarTransformer,
            PrivacyPhase phase,
            ProcessingBudget budget
    ) throws JacksonException {
        budget.acceptNode();
        switch (token) {
            case START_OBJECT -> writeObject(parser, generator, scalarTransformer, phase, budget);
            case START_ARRAY -> writeArray(parser, generator, scalarTransformer, phase, budget);
            case VALUE_STRING -> {
                String text = parser.getString();
                budget.acceptInputCharacters(text.length());
                writeTransformedScalar(generator, text, null, scalarTransformer.apply(text), phase);
            }
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                String lexeme = parser.getString();
                budget.acceptInputCharacters(lexeme.length());
                Number number = losslessNumber(lexeme, token, phase, budget.limits);
                writeTransformedScalar(
                        generator,
                        number,
                        lexeme,
                        scalarTransformer.apply(number),
                        phase
                );
            }
            case VALUE_TRUE -> generator.writeBoolean(true);
            case VALUE_FALSE -> generator.writeBoolean(false);
            case VALUE_NULL -> generator.writeNull();
            default -> throw new InvalidJsonPayload();
        }
    }

    private static void writeObject(
            JsonParser parser,
            JsonGenerator generator,
            Function<Object, Object> scalarTransformer,
            PrivacyPhase phase,
            ProcessingBudget budget
    ) throws JacksonException {
        generator.writeStartObject();
        Set<String> transformedNames = new HashSet<>();
        while (true) {
            JsonToken token = parser.nextToken();
            if (token == JsonToken.END_OBJECT) {
                generator.writeEndObject();
                return;
            }
            if (token != JsonToken.PROPERTY_NAME) {
                throw new InvalidJsonPayload();
            }
            String originalName = parser.getString();
            budget.acceptNode();
            budget.acceptInputCharacters(originalName.length());
            Object transformedName = scalarTransformer.apply(originalName);
            if (!(transformedName instanceof String name)) {
                throw PrivacyJsonPayloadTransformer.transformationConflict(
                        phase,
                        "JSON property protection changed its scalar type"
                );
            }
            if (!transformedNames.add(name)) {
                throw PrivacyJsonPayloadTransformer.transformationConflict(
                        phase,
                        "PII transformation produced duplicate JSON properties"
                );
            }
            generator.writeName(name);
            JsonToken valueToken = parser.nextToken();
            if (valueToken == null) {
                throw new InvalidJsonPayload();
            }
            writeValue(parser, generator, valueToken, scalarTransformer, phase, budget);
        }
    }

    private static void writeArray(
            JsonParser parser,
            JsonGenerator generator,
            Function<Object, Object> scalarTransformer,
            PrivacyPhase phase,
            ProcessingBudget budget
    ) throws JacksonException {
        generator.writeStartArray();
        while (true) {
            JsonToken token = parser.nextToken();
            if (token == JsonToken.END_ARRAY) {
                generator.writeEndArray();
                return;
            }
            if (token == null) {
                throw new InvalidJsonPayload();
            }
            writeValue(parser, generator, token, scalarTransformer, phase, budget);
        }
    }

    private static void writeTransformedScalar(
            JsonGenerator generator,
            Object original,
            String numericLexeme,
            Object transformed,
            PrivacyPhase phase
    ) throws JacksonException {
        if (transformed instanceof String text) {
            generator.writeString(text);
            return;
        }
        if (transformed instanceof Number number) {
            if (numericLexeme != null && numericallyEqual(original, number)) {
                generator.writeRawValue(numericLexeme);
                return;
            }
            generator.writeRawValue(number.toString());
            return;
        }
        if (transformed instanceof Boolean bool) {
            generator.writeBoolean(bool);
            return;
        }
        if (transformed == null) {
            generator.writeNull();
            return;
        }
        throw PrivacyJsonPayloadTransformer.transformationConflict(
                phase,
                "JSON protection produced an unsupported scalar type"
        );
    }

    private static Number losslessNumber(
            String lexeme, JsonToken token, PrivacyPhase phase, PrivacyProcessingLimits limits
    ) {
        if (token == JsonToken.VALUE_NUMBER_INT) {
            BigInteger value = new BigInteger(lexeme);
            try {
                return value.intValueExact();
            } catch (ArithmeticException ignored) {
                try {
                    return value.longValueExact();
                } catch (ArithmeticException alsoIgnored) {
                    return value;
                }
            }
        }
        BigDecimal value;
        try {
            value = new BigDecimal(lexeme);
        } catch (NumberFormatException ignored) {
            throw PrivacyJsonPayloadTransformer.payloadLimitExceeded(phase);
        }
        PrivacyJsonPayloadTransformer.requireWithinLimit(
                ScalarAnalysisText.plainDecimalLength(value),
                Math.min(limits.maxValueTreeCharacters(), limits.maxTextCharacters()),
                phase
        );
        return new BigDecimal(ScalarAnalysisText.toAnalysisText(value));
    }

    private static boolean numericallyEqual(Object left, Number right) {
        if (!(left instanceof Number leftNumber)) {
            return false;
        }
        return new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(right.toString())) == 0;
    }

    static final class InvalidJsonPayload extends RuntimeException {
    }

    /** Internal output-limit signal with stack-trace creation disabled. */
    static final class OutputLimitExceeded extends RuntimeException {

        OutputLimitExceeded() {
            super(null, null, false, false);
        }
    }

    private static final class ProcessingBudget {

        private final PrivacyPhase phase;
        private final PrivacyProcessingLimits limits;
        private long nodeCount;
        private long inputCharacters;

        private ProcessingBudget(PrivacyPhase phase, PrivacyProcessingLimits limits) {
            this.phase = phase;
            this.limits = limits;
        }

        private void acceptNode() {
            PrivacyJsonPayloadTransformer.requireWithinLimit(
                    ++this.nodeCount,
                    this.limits.maxValueTreeNodes(),
                    this.phase
            );
        }

        private void acceptInputCharacters(int characterCount) {
            this.inputCharacters += characterCount;
            PrivacyJsonPayloadTransformer.requireWithinLimit(
                    this.inputCharacters,
                    this.limits.maxValueTreeCharacters(),
                    this.phase
            );
        }
    }
}
