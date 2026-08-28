package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;

/** Applies text tokenization, redaction, and detokenization for one core service. */
final class PrivacyTextTransformer {

    private final PiiAnalysisCoordinator analysisCoordinator;

    PrivacyTextTransformer(PiiAnalysisCoordinator analysisCoordinator) {
        this.analysisCoordinator = analysisCoordinator;
    }

    PiiTokenizationResult analyzeAndTokenize(String text, PrivacyContext context) {
        PiiAnalysisResult analysis = this.analysisCoordinator.analyzeDetailed(text);
        String tokenizedText = tokenizeResolved(text, analysis.spans(), context);
        return new PiiTokenizationResult(tokenizedText, analysis);
    }

    String tokenizeResolved(
            String text,
            List<ResolvedPiiSpan> spans,
            PrivacyContext context
    ) {
        return tokenizePrepared(text, protectionSpans(spans), context);
    }

    String tokenize(String text, List<PiiSpan> spans, PrivacyContext context) {
        List<ResolvedPiiSpan> resolved = this.analysisCoordinator.resolveSuppliedSpans(text, spans);
        return tokenizePrepared(text, protectionSpans(resolved), context);
    }

    String tokenize(String text, PrivacyContext context) {
        return analyzeAndTokenize(text, context).tokenizedText();
    }

    String redact(String text) {
        PiiAnalysisCoordinator.requireTextInputWithinLimit(text);
        if (text == null || text.isBlank()) {
            return text;
        }
        return redactPrepared(text, protectionSpans(this.analysisCoordinator.analyze(text)));
    }

    String redact(String text, List<PiiSpan> spans) {
        List<ResolvedPiiSpan> resolved = this.analysisCoordinator.resolveSuppliedSpans(text, spans);
        return redactPrepared(text, protectionSpans(resolved));
    }

    String redact(String text, List<PiiSpan> spans, PrivacyContext context) {
        List<ResolvedPiiSpan> resolved = this.analysisCoordinator.resolveSuppliedSpans(text, spans);
        return redactPrepared(
                text,
                excludeKnownTokens(text, protectionSpans(resolved), context)
        );
    }

    String redact(String text, PrivacyContext context) {
        PiiAnalysisCoordinator.requireTextInputWithinLimit(text);
        if (text == null || text.isBlank()) {
            return text;
        }
        List<ProtectionSpan> spans = excludeKnownTokens(
                text,
                protectionSpans(this.analysisCoordinator.analyze(text)),
                context
        );
        return redactPrepared(text, spans);
    }

    boolean containsPii(String text, PrivacyContext context) {
        PiiAnalysisCoordinator.requireTextInputWithinLimit(text);
        if (text == null || text.isBlank()) {
            return false;
        }
        return !excludeKnownTokens(
                text,
                protectionSpans(this.analysisCoordinator.analyze(text)),
                context
        ).isEmpty();
    }

    boolean containsPii(String text, List<PiiSpan> spans, PrivacyContext context) {
        List<ResolvedPiiSpan> resolved = this.analysisCoordinator.resolveSuppliedSpans(text, spans);
        return !excludeKnownTokens(
                text,
                protectionSpans(resolved),
                context
        ).isEmpty();
    }

    String detokenize(String text, PrivacyContext context, Set<String> allowedEntityTypes) {
        if (text == null || text.isBlank()) {
            return text;
        }
        if (!context.hasTokens()) {
            return text;
        }

        Matcher matcher = OpaquePiiTokenFormat.canonicalTokenPattern().matcher(text);
        StringBuilder detokenizedText = null;
        int cursor = 0;
        while (matcher.find()) {
            String original = context.originalTextForToken(matcher.group(), allowedEntityTypes);
            if (original == null) {
                continue;
            }
            if (detokenizedText == null) {
                detokenizedText = new StringBuilder(Math.min(
                        text.length(),
                        PrivacyService.MAX_TRANSFORMED_TEXT_CHARACTERS
                ));
            }
            appendBounded(detokenizedText, text, cursor, matcher.start(), PrivacyPhase.DETOKENIZATION);
            appendBounded(detokenizedText, original, PrivacyPhase.DETOKENIZATION);
            cursor = matcher.end();
        }
        if (detokenizedText == null) {
            return text;
        }
        appendBounded(detokenizedText, text, cursor, text.length(), PrivacyPhase.DETOKENIZATION);
        return detokenizedText.toString();
    }

    private String tokenizePrepared(String text, List<ProtectionSpan> spans, PrivacyContext context) {
        List<ProtectionSpan> preparedSpans = excludeKnownTokens(text, spans, context);
        if (preparedSpans.isEmpty()) {
            return text;
        }

        List<ProtectionSpan> ordered = orderedSpans(preparedSpans);
        int capacity = boundedTransformedLength(
                text,
                ordered,
                PrivacyPhase.TOKENIZATION,
                span -> OpaquePiiTokenFormat.maximumGeneratedTokenLength(span.entityType())
        );
        StringBuilder tokenizedText = new StringBuilder(capacity);
        int cursor = 0;
        for (ProtectionSpan span : ordered) {
            tokenizedText.append(text, cursor, span.start());
            tokenizedText.append(context.tokenFor(
                    span.entityType(),
                    text.substring(span.start(), span.end())
            ));
            cursor = span.end();
        }
        return tokenizedText.append(text, cursor, text.length()).toString();
    }

    private static String redactPrepared(String text, List<ProtectionSpan> spans) {
        if (spans.isEmpty()) {
            return text;
        }

        List<ProtectionSpan> ordered = orderedSpans(spans);
        int capacity = boundedTransformedLength(
                text,
                ordered,
                PrivacyPhase.REDACTION,
                span -> redactionMarker(span.entityType()).length()
        );
        StringBuilder redactedText = new StringBuilder(capacity);
        int cursor = 0;
        for (ProtectionSpan span : ordered) {
            redactedText.append(text, cursor, span.start());
            redactedText.append(redactionMarker(span.entityType()));
            cursor = span.end();
        }
        return redactedText.append(text, cursor, text.length()).toString();
    }

    private static List<ProtectionSpan> excludeKnownTokens(
            String text,
            List<ProtectionSpan> spans,
            PrivacyContext context
    ) {
        if (spans.isEmpty() || !context.hasTokens()) {
            return spans;
        }
        List<TextRange> protectedRanges = knownTokenRanges(text, context);
        if (protectedRanges.isEmpty()) {
            return spans;
        }

        List<ProtectionSpan> spansOutsideKnownTokens = new ArrayList<>();
        List<ProtectionSpan> ordered = orderedSpans(spans);
        int firstPossibleRange = 0;
        for (ProtectionSpan span : ordered) {
            while (firstPossibleRange < protectedRanges.size()
                    && protectedRanges.get(firstPossibleRange).end() <= span.start()) {
                firstPossibleRange++;
            }
            int cursor = span.start();
            for (int index = firstPossibleRange; index < protectedRanges.size(); index++) {
                TextRange range = protectedRanges.get(index);
                if (range.start() >= span.end()) {
                    break;
                }
                if (range.start() > cursor) {
                    spansOutsideKnownTokens.add(segment(span, cursor, Math.min(range.start(), span.end())));
                }
                cursor = Math.max(cursor, range.end());
                if (cursor >= span.end()) {
                    break;
                }
            }
            if (cursor < span.end()) {
                spansOutsideKnownTokens.add(segment(span, cursor, span.end()));
            }
        }
        return List.copyOf(spansOutsideKnownTokens);
    }

    private static List<TextRange> knownTokenRanges(String text, PrivacyContext context) {
        List<TextRange> ranges = new ArrayList<>();
        Matcher matcher = OpaquePiiTokenFormat.canonicalTokenPattern().matcher(text);
        while (matcher.find()) {
            if (context.ownsToken(matcher.group())) {
                ranges.add(new TextRange(matcher.start(), matcher.end()));
            }
        }
        return List.copyOf(ranges);
    }

    private static List<ProtectionSpan> orderedSpans(List<ProtectionSpan> spans) {
        return spans.stream()
                .sorted(Comparator.comparingInt(ProtectionSpan::start).thenComparingInt(ProtectionSpan::end))
                .toList();
    }

    private static int boundedTransformedLength(
            String text,
            List<ProtectionSpan> spans,
            PrivacyPhase phase,
            ToIntFunction<ProtectionSpan> replacementLength
    ) {
        long length = text.length();
        for (ProtectionSpan span : spans) {
            length += (long) replacementLength.applyAsInt(span) - (span.end() - span.start());
            requireOutputLength(length, phase);
        }
        return (int) length;
    }

    private static String redactionMarker(String entityType) {
        return "[REDACTED_" + entityType + "]";
    }

    private static void appendBounded(StringBuilder target, String value, PrivacyPhase phase) {
        requireOutputLength((long) target.length() + value.length(), phase);
        target.append(value);
    }

    private static void appendBounded(
            StringBuilder target,
            String value,
            int start,
            int end,
            PrivacyPhase phase
    ) {
        requireOutputLength((long) target.length() + end - start, phase);
        target.append(value, start, end);
    }

    private static void requireOutputLength(long length, PrivacyPhase phase) {
        if (length > PrivacyService.MAX_TRANSFORMED_TEXT_CHARACTERS) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                    phase,
                    "Privacy text transformation exceeded the bounded output limit"
            );
        }
    }

    private static List<ProtectionSpan> protectionSpans(List<ResolvedPiiSpan> spans) {
        return spans.stream()
                .map(span -> new ProtectionSpan(span.entityType(), span.start(), span.end()))
                .toList();
    }

    private static ProtectionSpan segment(ProtectionSpan span, int start, int end) {
        return new ProtectionSpan(span.entityType(), start, end);
    }

    private record TextRange(int start, int end) {
    }

    private record ProtectionSpan(String entityType, int start, int end) {
    }
}
