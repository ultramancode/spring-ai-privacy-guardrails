package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.ResolvedPiiSpan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Batches JSON scalar text for analysis and remaps results to scalar-local offsets. */
final class PrivacyJsonScalarBatchAnalyzer {

    // A NUL sentinel keeps adjacent JSON scalars distinct during batched analysis.
    // Any analyzer span that still crosses the boundary is rejected before offset remapping.
    private static final String BATCH_SEPARATOR = "\n\u0000\n";

    private PrivacyJsonScalarBatchAnalyzer() {
    }

    static Map<String, List<PiiSpan>> analyze(
            PrivacyService privacyService,
            List<String> analysisTexts,
            PrivacyPhase phase
    ) {
        Objects.requireNonNull(privacyService, "privacyService must not be null");
        Objects.requireNonNull(analysisTexts, "analysisTexts must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        requireAnalysisSizeWithinLimit(analysisTexts, phase);

        Map<String, List<PiiSpan>> spansByText = new LinkedHashMap<>();
        StringBuilder batch = new StringBuilder();
        List<BatchSegment> segments = new ArrayList<>();
        AnalysisResultBudget resultBudget = new AnalysisResultBudget(phase);
        for (String text : analysisTexts) {
            int separatorLength = segments.isEmpty() ? 0 : BATCH_SEPARATOR.length();
            if (!segments.isEmpty()
                    && batch.length() + separatorLength + text.length()
                    > PrivacyJsonPayloadTransformer.ANALYSIS_BATCH_TARGET_CHARACTERS) {
                analyzeBatch(privacyService, batch, segments, spansByText, phase, resultBudget);
                batch.setLength(0);
                segments.clear();
                separatorLength = 0;
            }
            if (separatorLength > 0) {
                batch.append(BATCH_SEPARATOR);
            }
            int start = batch.length();
            batch.append(text);
            segments.add(new BatchSegment(text, start, batch.length()));
        }
        if (!segments.isEmpty()) {
            analyzeBatch(privacyService, batch, segments, spansByText, phase, resultBudget);
        }

        spansByText.replaceAll((ignored, spans) -> List.copyOf(spans));
        return Map.copyOf(spansByText);
    }

    private static void requireAnalysisSizeWithinLimit(
            List<String> analysisTexts,
            PrivacyPhase phase
    ) {
        long analysisCharacters = 0L;
        for (int index = 0; index < analysisTexts.size(); index++) {
            String text = Objects.requireNonNull(
                    analysisTexts.get(index),
                    "analysisTexts must not contain null values"
            );
            analysisCharacters += index == 0 ? 0L : BATCH_SEPARATOR.length();
            analysisCharacters += text.length();
            PrivacyJsonPayloadTransformer.requireWithinLimit(
                    analysisCharacters,
                    PrivacyJsonPayloadTransformer.MAX_ANALYSIS_CHARACTERS,
                    phase
            );
        }
    }

    private static void analyzeBatch(
            PrivacyService privacyService,
            StringBuilder batch,
            List<BatchSegment> segments,
            Map<String, List<PiiSpan>> spansByText,
            PrivacyPhase phase,
            AnalysisResultBudget resultBudget
    ) {
        List<ResolvedPiiSpan> resolvedSpans = privacyService.analyze(batch.toString());
        resultBudget.accept(resolvedSpans.size());
        int segmentIndex = 0;
        for (ResolvedPiiSpan span : resolvedSpans) {
            while (segmentIndex < segments.size()
                    && span.start() >= segments.get(segmentIndex).end()) {
                segmentIndex++;
            }
            if (segmentIndex >= segments.size()) {
                throw PrivacyJsonPayloadTransformer.scalarBoundaryConflict(phase);
            }
            BatchSegment segment = segments.get(segmentIndex);
            if (span.start() < segment.start() || span.end() > segment.end()) {
                throw PrivacyJsonPayloadTransformer.scalarBoundaryConflict(phase);
            }
            double score = span.evidence().stream()
                    .mapToDouble(evidence -> evidence.score())
                    .max()
                    .orElseThrow();
            spansByText.computeIfAbsent(segment.text(), ignored -> new ArrayList<>()).add(new PiiSpan(
                    span.entityType(),
                    span.start() - segment.start(),
                    span.end() - segment.start(),
                    score
            ));
        }
    }

    private record BatchSegment(String text, int start, int end) {
    }

    private static final class AnalysisResultBudget {

        private final PrivacyPhase phase;
        private int spanCount;

        private AnalysisResultBudget(PrivacyPhase phase) {
            this.phase = phase;
        }

        private void accept(int additionalSpans) {
            long updatedSpanCount = (long) this.spanCount + additionalSpans;
            PrivacyJsonPayloadTransformer.requireWithinLimit(
                    updatedSpanCount,
                    PiiAnalyzer.MAX_RESULT_SPANS,
                    this.phase
            );
            this.spanCount = (int) updatedSpanCount;
        }
    }
}
