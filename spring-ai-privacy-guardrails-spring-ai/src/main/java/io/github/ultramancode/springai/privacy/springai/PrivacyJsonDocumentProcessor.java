package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;

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

    private static final int MAX_EXPANDED_NUMBER_CHARACTERS = 4_096;
    // Every container can add one end token, so Jackson may count twice as many tokens as nodes.
    private static final long MAX_JSON_TOKENS =
            2L * PrivacyJsonPayloadTransformer.MAX_JSON_NODES;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(
            JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxDocumentLength(PrivacyJsonPayloadTransformer.MAX_PAYLOAD_CHARACTERS)
                            .maxStringLength(PrivacyJsonPayloadTransformer.MAX_STRING_SCALAR_CHARACTERS)
                            .maxNameLength(PrivacyJsonPayloadTransformer.MAX_STRING_SCALAR_CHARACTERS)
                            .maxNumberLength(PrivacyJsonPayloadTransformer.MAX_NUMBER_LEXEME_CHARACTERS)
                            .maxNestingDepth(PrivacyJsonPayloadTransformer.MAX_JSON_DEPTH)
                            .maxTokenCount(MAX_JSON_TOKENS)
                            .build())
                    .build()
    );

    private PrivacyJsonDocumentProcessor() {
    }

    static String transform(
            String payload,
            Function<Object, Object> scalarTransformer,
            PrivacyPhase phase
    ) throws JacksonException {
        validateAndCollectAnalysisTexts(payload, phase);
        return rewrite(payload, scalarTransformer, phase);
    }

    static List<String> validateAndCollectAnalysisTexts(
            String payload,
            PrivacyPhase phase
    ) throws JacksonException {
        validateSingleJsonValue(payload);
        Set<String> uniqueAnalysisTexts = new LinkedHashSet<>();
        ProcessingBudget budget = new ProcessingBudget(phase);
        try (JsonParser parser = OBJECT_MAPPER.createParser(payload)) {
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token != JsonToken.END_OBJECT && token != JsonToken.END_ARRAY) {
                    budget.acceptNode();
                }
                if (token == JsonToken.PROPERTY_NAME || token == JsonToken.VALUE_STRING) {
                    String scalar = parser.getString();
                    budget.acceptScalar(scalar.length());
                    if (!scalar.isBlank()) {
                        uniqueAnalysisTexts.add(scalar);
                    }
                } else if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                    String lexeme = parser.getString();
                    budget.acceptNumber(lexeme.length());
                    uniqueAnalysisTexts.add(analysisText(losslessNumber(lexeme, token, phase)));
                }
            }
        }
        return List.copyOf(uniqueAnalysisTexts);
    }

    static String rewrite(
            String payload,
            Function<Object, Object> scalarTransformer,
            PrivacyPhase phase
    ) throws JacksonException {
        PrivacyJsonBoundedWriter writer = new PrivacyJsonBoundedWriter(payload.length());
        ProcessingBudget budget = new ProcessingBudget(phase);
        Map<Object, Object> transformedScalars = new HashMap<>();
        Function<Object, Object> memoizedTransformer = scalar -> {
            if (transformedScalars.containsKey(scalar)) {
                return transformedScalars.get(scalar);
            }
            Object transformed = scalarTransformer.apply(scalar);
            transformedScalars.put(scalar, transformed);
            return transformed;
        };
        try (JsonParser parser = OBJECT_MAPPER.createParser(payload);
             JsonGenerator generator = OBJECT_MAPPER.createGenerator(writer)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new InvalidJsonPayload();
            }
            writeValue(parser, generator, first, memoizedTransformer, phase, budget);
            if (parser.nextToken() != null) {
                throw new InvalidJsonPayload();
            }
        }
        return PrivacyJsonPayloadTransformer.requireTransformedResult(writer.toString(), phase);
    }

    static String analysisText(Object scalar) {
        if (scalar instanceof String text) {
            return text;
        }
        if (scalar instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (scalar instanceof Number number) {
            return number.toString();
        }
        throw new IllegalArgumentException("scalar must be a JSON string or number");
    }

    private static void validateSingleJsonValue(String payload) throws JacksonException {
        try (JsonParser parser = OBJECT_MAPPER.createParser(payload)) {
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
                budget.acceptScalar(text.length());
                writeTransformedScalar(generator, text, null, scalarTransformer.apply(text), phase);
            }
            case VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> {
                String lexeme = parser.getString();
                budget.acceptNumber(lexeme.length());
                Number number = losslessNumber(lexeme, token, phase);
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
            budget.acceptScalar(originalName.length());
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

    private static Number losslessNumber(String lexeme, JsonToken token, PrivacyPhase phase) {
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
                expandedNumberLength(value),
                MAX_EXPANDED_NUMBER_CHARACTERS,
                phase
        );
        return new BigDecimal(value.toPlainString());
    }

    private static boolean numericallyEqual(Object left, Number right) {
        if (!(left instanceof Number leftNumber)) {
            return false;
        }
        return new BigDecimal(leftNumber.toString()).compareTo(new BigDecimal(right.toString())) == 0;
    }

    private static long expandedNumberLength(BigDecimal value) {
        // Bound the plain representation before materializing a potentially hostile exponent.
        long sign = value.signum() < 0 ? 1L : 0L;
        long precision = value.precision();
        long scale = value.scale();
        if (scale <= 0) {
            return sign + precision - scale;
        }
        long integerDigits = precision - scale;
        if (integerDigits > 0) {
            return sign + precision + 1L;
        }
        return sign + 2L - integerDigits + precision;
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
        private int nodeCount;

        private ProcessingBudget(PrivacyPhase phase) {
            this.phase = phase;
        }

        private void acceptNode() {
            PrivacyJsonPayloadTransformer.requireWithinLimit(
                    ++this.nodeCount,
                    PrivacyJsonPayloadTransformer.MAX_JSON_NODES,
                    this.phase
            );
        }

        private void acceptScalar(int characterCount) {
            PrivacyJsonPayloadTransformer.requireWithinLimit(
                    characterCount,
                    PrivacyJsonPayloadTransformer.MAX_STRING_SCALAR_CHARACTERS,
                    this.phase
            );
        }

        private void acceptNumber(int characterCount) {
            PrivacyJsonPayloadTransformer.requireWithinLimit(
                    characterCount,
                    PrivacyJsonPayloadTransformer.MAX_NUMBER_LEXEME_CHARACTERS,
                    this.phase
            );
        }
    }
}
