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
    private final SessionAnalysis sessionAnalysis;
    private final PrivacyProcessingLimits processingLimits;

    PrivacyTextTransformer(
            PiiAnalysisCoordinator analysisCoordinator,
            SessionAnalysis sessionAnalysis,
            PrivacyProcessingLimits processingLimits
    ) {
        this.analysisCoordinator = analysisCoordinator;
        this.sessionAnalysis = sessionAnalysis;
        this.processingLimits = processingLimits;
    }

    PiiTokenizationResult analyzeAndTokenize(String text, PrivacyContext context) {
        PiiAnalysisResult analysis = this.sessionAnalysis.analyzeEvidence(text, context).result();
        String tokenizedText = tokenizeWithResolvedSpans(text, analysis.spans(), context);
        if (tokenizedText != null && analysis.failures().isEmpty()) {
            context.retainCompletedTokenization(tokenizedText);
        }
        return new PiiTokenizationResult(tokenizedText, analysis);
    }

    String tokenizeWithResolvedSpans(
            String text,
            List<ResolvedPiiSpan> resolvedSpans,
            PrivacyContext context
    ) {
        return tokenizePrepared(text, protectionSpans(resolvedSpans), context);
    }

    String tokenize(String text, List<PiiSpan> spans, PrivacyContext context) {
        List<ResolvedPiiSpan> resolvedSpans = this.analysisCoordinator.resolveSuppliedSpans(text, spans);
        return tokenizePrepared(text, protectionSpans(resolvedSpans), context);
    }

    String tokenize(String text, PrivacyContext context) {
        if (canReuseCompletedTokenization(text, context)) {
            return requireOutputWithinLimit(text, PrivacyPhase.TOKENIZATION);
        }
        return analyzeAndTokenize(text, context).tokenizedText();
    }

    boolean canReuseCompletedTokenization(String text, PrivacyContext context) {
        this.analysisCoordinator.requireTextInputWithinLimit(text);
        context.requireActive();
        if (Thread.currentThread().isInterrupted()) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.ANALYSIS_INTERRUPTED, PrivacyPhase.ANALYSIS,
                    "PII analysis interrupted");
        }
        return text != null && context.isCompletedTokenization(text);
    }

    String redact(String text) {
        this.analysisCoordinator.requireTextInputWithinLimit(text);
        if (text == null || text.isBlank()) {
            return requireOutputWithinLimit(text, PrivacyPhase.REDACTION);
        }
        List<ResolvedPiiSpan> resolvedSpans = this.analysisCoordinator.analyzeEvidence(text).result().spans();
        return redactPrepared(text, protectionSpans(resolvedSpans));
    }

    String redact(String text, List<PiiSpan> spans) {
        List<ResolvedPiiSpan> resolvedSpans = this.analysisCoordinator.resolveSuppliedSpans(text, spans);
        return redactPrepared(text, protectionSpans(resolvedSpans));
    }

    String redact(String text, List<PiiSpan> spans, PrivacyContext context) {
        List<ResolvedPiiSpan> resolvedSpans = this.analysisCoordinator.resolveSuppliedSpans(text, spans);
        return redactPrepared(
                text,
                excludeKnownTokens(text, protectionSpans(resolvedSpans), context)
        );
    }

    String redact(String text, PrivacyContext context) {
        this.analysisCoordinator.requireTextInputWithinLimit(text);
        if (text == null || text.isBlank()) {
            return requireOutputWithinLimit(text, PrivacyPhase.REDACTION);
        }
        List<ResolvedPiiSpan> resolvedSpans = this.sessionAnalysis.analyzeEvidence(text, context)
                .result().spans();
        List<ProtectionSpan> spans = excludeKnownTokens(text, protectionSpans(resolvedSpans), context);
        return redactPrepared(text, spans);
    }

    boolean containsPii(String text, PrivacyContext context) {
        this.analysisCoordinator.requireTextInputWithinLimit(text);
        if (text == null || text.isBlank()) {
            return false;
        }
        List<ResolvedPiiSpan> resolvedSpans = this.sessionAnalysis.analyzeEvidence(text, context)
                .result().spans();
        return !excludeKnownTokens(text, protectionSpans(resolvedSpans), context).isEmpty();
    }

    boolean containsPii(String text, List<PiiSpan> spans, PrivacyContext context) {
        List<ResolvedPiiSpan> resolvedSpans = this.analysisCoordinator.resolveSuppliedSpans(text, spans);
        return !excludeKnownTokens(
                text,
                protectionSpans(resolvedSpans),
                context
        ).isEmpty();
    }

    String detokenize(String text, PrivacyContext context, Set<String> allowedEntityTypes) {
        if (text == null || text.isBlank()) {
            return requireOutputWithinLimit(text, PrivacyPhase.DETOKENIZATION);
        }
        if (!context.hasTokens()) {
            return requireOutputWithinLimit(text, PrivacyPhase.DETOKENIZATION);
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
                        this.processingLimits.maxOutputCharacters()
                ));
            }
            appendBounded(detokenizedText, text, cursor, matcher.start(), PrivacyPhase.DETOKENIZATION);
            appendBounded(detokenizedText, original, PrivacyPhase.DETOKENIZATION);
            cursor = matcher.end();
        }
        if (detokenizedText == null) {
            return requireOutputWithinLimit(text, PrivacyPhase.DETOKENIZATION);
        }
        appendBounded(detokenizedText, text, cursor, text.length(), PrivacyPhase.DETOKENIZATION);
        return detokenizedText.toString();
    }

    private String tokenizePrepared(String text, List<ProtectionSpan> spans, PrivacyContext context) {
        List<ProtectionSpan> preparedSpans = excludeKnownTokens(text, spans, context);
        if (preparedSpans.isEmpty()) {
            return requireOutputWithinLimit(text, PrivacyPhase.TOKENIZATION);
        }

        List<ProtectionSpan> ordered = orderedSpans(preparedSpans);
        // Reject guaranteed overflow before creating mappings, then check actual token lengths.
        int capacity = boundedTransformedLength(
                text,
                ordered,
                PrivacyPhase.TOKENIZATION,
                span -> OpaquePiiTokenFormat.minimumGeneratedTokenLength(span.entityType())
        );
        StringBuilder tokenizedText = new StringBuilder(capacity);
        int cursor = 0;
        for (ProtectionSpan span : ordered) {
            appendBounded(tokenizedText, text, cursor, span.start(), PrivacyPhase.TOKENIZATION);
            String token = context.tokenFor(
                    span.entityType(),
                    text.substring(span.start(), span.end())
            );
            appendBounded(tokenizedText, token, PrivacyPhase.TOKENIZATION);
            cursor = span.end();
        }
        appendBounded(tokenizedText, text, cursor, text.length(), PrivacyPhase.TOKENIZATION);
        return tokenizedText.toString();
    }

    private String redactPrepared(String text, List<ProtectionSpan> spans) {
        if (spans.isEmpty()) {
            return requireOutputWithinLimit(text, PrivacyPhase.REDACTION);
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

    private int boundedTransformedLength(
            String text,
            List<ProtectionSpan> spans,
            PrivacyPhase phase,
            ToIntFunction<ProtectionSpan> replacementLength
    ) {
        long length = text.length();
        for (ProtectionSpan span : spans) {
            length += (long) replacementLength.applyAsInt(span) - (span.end() - span.start());
        }
        requireOutputLength(length, phase);
        return (int) length;
    }

    private static String redactionMarker(String entityType) {
        return "[REDACTED_" + entityType + "]";
    }

    private void appendBounded(StringBuilder target, String value, PrivacyPhase phase) {
        requireOutputLength((long) target.length() + value.length(), phase);
        target.append(value);
    }

    private void appendBounded(
            StringBuilder target,
            String value,
            int start,
            int end,
            PrivacyPhase phase
    ) {
        requireOutputLength((long) target.length() + end - start, phase);
        target.append(value, start, end);
    }

    private String requireOutputWithinLimit(String text, PrivacyPhase phase) {
        if (text != null) {
            requireOutputLength(text.length(), phase);
        }
        return text;
    }

    void requireOutputLength(long length, PrivacyPhase phase) {
        if (length > this.processingLimits.maxOutputCharacters()) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                    phase,
                    "Privacy text transformation exceeded the configured output limit"
            );
        }
    }

    private static List<ProtectionSpan> protectionSpans(List<ResolvedPiiSpan> resolvedSpans) {
        return resolvedSpans.stream()
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
