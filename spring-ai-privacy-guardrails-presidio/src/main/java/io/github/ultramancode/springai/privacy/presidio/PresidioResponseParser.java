package io.github.ultramancode.springai.privacy.presidio;

import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Parses and validates Presidio response payloads against the adapter contract. */
final class PresidioResponseParser {

    private static final int MAX_RESPONSE_DEPTH = 64;
    private static final int MAX_RESPONSE_NAME_CHARACTERS = 256;
    private static final int MAX_RESPONSE_STRING_CHARACTERS = 250_000;
    private static final int MAX_RESPONSE_NUMBER_CHARACTERS = 1_000;
    // Four required scalar fields use ten JSON tokens. Segmented responses may add
    // two array tokens per source text. The remaining budget covers ignored fields.
    private static final long MAX_RESPONSE_TOKENS = 1_300_002L;
    private final JsonFactory jsonFactory;

    PresidioResponseParser(int maxResponseBytes) {
        this.jsonFactory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(maxResponseBytes)
                        .maxStringLength(MAX_RESPONSE_STRING_CHARACTERS)
                        .maxNameLength(MAX_RESPONSE_NAME_CHARACTERS)
                        .maxNumberLength(MAX_RESPONSE_NUMBER_CHARACTERS)
                        .maxNestingDepth(MAX_RESPONSE_DEPTH)
                        .maxTokenCount(MAX_RESPONSE_TOKENS)
                        .build())
                .build();
    }

    List<PiiSpan> parse(byte[] responseBody, String sourceText) {
        Utf16OffsetIndex offsetIndex = Utf16OffsetIndex.from(
                Objects.requireNonNull(sourceText, "sourceText must not be null")
        );
        try (JsonParser parser = this.jsonFactory.createParser(ObjectReadContext.empty(), responseBody)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw invalidResponseContract();
            }
            List<PiiSpan> spans = readSpans(parser, offsetIndex, new SpanBudget());
            if (parser.nextToken() != null) {
                throw invalidResponseContract();
            }
            return spans;
        } catch (PresidioCallException failure) {
            throw failure;
        } catch (JacksonException exception) {
            throw new PresidioCallException(
                    "Could not parse Presidio analyzer response",
                    false,
                    PrivacyFailureCode.ANALYZER_RESPONSE_INVALID
            );
        }
    }

    List<List<PiiSpan>> parseSegments(byte[] responseBody, List<String> sourceTexts) {
        Objects.requireNonNull(sourceTexts, "sourceTexts must not be null");
        try (JsonParser parser = this.jsonFactory.createParser(ObjectReadContext.empty(), responseBody)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw invalidResponseContract();
            }

            SpanBudget spanBudget = new SpanBudget();
            List<List<PiiSpan>> results = new ArrayList<>(sourceTexts.size());
            for (String sourceText : sourceTexts) {
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    throw invalidResponseContract();
                }
                results.add(readSpans(
                        parser,
                        Utf16OffsetIndex.from(Objects.requireNonNull(
                                sourceText,
                                "sourceTexts must not contain null values"
                        )),
                        spanBudget
                ));
            }
            if (parser.nextToken() != JsonToken.END_ARRAY || parser.nextToken() != null) {
                throw invalidResponseContract();
            }
            return List.copyOf(results);
        } catch (PresidioCallException failure) {
            throw failure;
        } catch (JacksonException exception) {
            throw new PresidioCallException(
                    "Could not parse Presidio analyzer response",
                    false,
                    PrivacyFailureCode.ANALYZER_RESPONSE_INVALID
            );
        }
    }

    private List<PiiSpan> readSpans(
            JsonParser parser,
            Utf16OffsetIndex offsetIndex,
            SpanBudget spanBudget
    ) throws JacksonException {
        List<PiiSpan> spans = new ArrayList<>();
        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (token == null || token != JsonToken.START_OBJECT) {
                throw invalidResponseContract();
            }
            spanBudget.accept();
            spans.add(readSpan(parser, offsetIndex));
        }
        return List.copyOf(spans);
    }

    private PiiSpan readSpan(JsonParser parser, Utf16OffsetIndex offsetIndex) throws JacksonException {
        String entityType = null;
        Integer unicodeCodePointStart = null;
        Integer unicodeCodePointEnd = null;
        Double score = null;
        Set<String> fields = new HashSet<>();

        JsonToken token;
        while ((token = parser.nextToken()) != JsonToken.END_OBJECT) {
            if (token == null || token != JsonToken.PROPERTY_NAME) {
                throw invalidResponseContract();
            }
            String field = parser.getString();
            JsonToken valueToken = parser.nextToken();
            if (valueToken == null || !fields.add(field)) {
                throw invalidResponseContract();
            }
            switch (field) {
                case "entity_type" -> entityType = readString(parser, valueToken);
                case "start" -> unicodeCodePointStart = readInteger(parser, valueToken);
                case "end" -> unicodeCodePointEnd = readInteger(parser, valueToken);
                case "score" -> score = readScore(parser, valueToken);
                default -> parser.skipChildren();
            }
        }

        if (entityType == null || unicodeCodePointStart == null
                || unicodeCodePointEnd == null || score == null) {
            throw invalidResponseContract();
        }
        Utf16Span utf16Span = offsetIndex.toUtf16Span(
                unicodeCodePointStart,
                unicodeCodePointEnd
        );
        if (utf16Span == null) {
            throw invalidResponseContract();
        }
        try {
            return new PiiSpan(entityType, utf16Span.start(), utf16Span.end(), score);
        } catch (IllegalArgumentException exception) {
            throw invalidResponseContract();
        }
    }

    private static String readString(JsonParser parser, JsonToken token) throws JacksonException {
        if (token != JsonToken.VALUE_STRING) {
            throw invalidResponseContract();
        }
        String value = parser.getString();
        if (value.isBlank()) {
            throw invalidResponseContract();
        }
        return value;
    }

    private static Integer readInteger(JsonParser parser, JsonToken token) throws JacksonException {
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw invalidResponseContract();
        }
        Number value = parser.getNumberValue();
        if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
            return value.intValue();
        }
        if (value instanceof Long longValue) {
            try {
                return Math.toIntExact(longValue);
            } catch (ArithmeticException ignored) {
                throw invalidResponseContract();
            }
        }
        if (value instanceof BigInteger bigInteger) {
            try {
                return bigInteger.intValueExact();
            } catch (ArithmeticException ignored) {
                throw invalidResponseContract();
            }
        }
        throw invalidResponseContract();
    }

    private static Double readScore(JsonParser parser, JsonToken token) throws JacksonException {
        if (token != JsonToken.VALUE_NUMBER_INT && token != JsonToken.VALUE_NUMBER_FLOAT) {
            throw invalidResponseContract();
        }
        double score = parser.getNumberValue().doubleValue();
        if (!Double.isFinite(score) || score < 0.0 || score > 1.0) {
            throw invalidResponseContract();
        }
        return score;
    }

    private static PresidioCallException invalidResponseContract() {
        return new PresidioCallException(
                "Presidio analyzer response violated the expected contract",
                false,
                PrivacyFailureCode.ANALYZER_RESPONSE_INVALID
        );
    }

    private static boolean isValidSpan(int start, int end, int maxLength) {
        return start >= 0 && end <= maxLength && start < end;
    }

    /** Presidio uses Unicode code-point offsets; core spans use Java UTF-16 indices. */
    private record Utf16OffsetIndex(int[] utf16OffsetByCodePoint, int utf16Length) {

        private static Utf16OffsetIndex from(String sourceText) {
            int codePointLength = sourceText.codePointCount(0, sourceText.length());
            int[] offsets = new int[codePointLength + 1];
            int utf16Offset = 0;
            for (int codePointOffset = 0; codePointOffset < codePointLength; codePointOffset++) {
                utf16Offset += Character.charCount(sourceText.codePointAt(utf16Offset));
                offsets[codePointOffset + 1] = utf16Offset;
            }
            return new Utf16OffsetIndex(offsets, sourceText.length());
        }

        private Utf16Span toUtf16Span(int codePointStart, int codePointEnd) {
            int codePointLength = this.utf16OffsetByCodePoint.length - 1;
            if (!isValidSpan(codePointStart, codePointEnd, codePointLength)) {
                return null;
            }
            int utf16Start = this.utf16OffsetByCodePoint[codePointStart];
            int utf16End = this.utf16OffsetByCodePoint[codePointEnd];
            if (!isValidSpan(utf16Start, utf16End, this.utf16Length)) {
                return null;
            }
            return new Utf16Span(utf16Start, utf16End);
        }
    }

    private record Utf16Span(int start, int end) {
    }

    private static final class SpanBudget {

        private int spanCount;

        private void accept() {
            if (this.spanCount >= PiiAnalyzer.MAX_RESULT_SPANS) {
                throw invalidResponseContract();
            }
            this.spanCount++;
        }
    }
}
