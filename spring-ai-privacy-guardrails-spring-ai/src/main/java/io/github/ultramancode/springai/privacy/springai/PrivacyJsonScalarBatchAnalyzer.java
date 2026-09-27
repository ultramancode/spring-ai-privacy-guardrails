package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.ResolvedPiiSpan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Groups texts extracted from JSON for independent batched analysis. */
final class PrivacyJsonScalarBatchAnalyzer {

    static final int TARGET_BATCH_CHARACTERS = 32_768;

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

        Map<String, List<PiiSpan>> spansByText = new LinkedHashMap<>();
        List<String> batch = new ArrayList<>();
        long batchCharacters = 0;
        int maxBatchSegments = privacyService.processingLimits().maxAnalysisSegments();
        AnalysisResultBudget resultBudget = new AnalysisResultBudget(
                phase,
                privacyService.processingLimits().maxResultSpans()
        );
        for (String text : analysisTexts) {
            if (!batch.isEmpty()
                    && (batch.size() >= maxBatchSegments
                    || batchCharacters + text.length() > TARGET_BATCH_CHARACTERS)) {
                analyzeBatch(privacyService, batch, spansByText, resultBudget);
                batch.clear();
                batchCharacters = 0;
            }
            batch.add(text);
            batchCharacters += text.length();
        }
        if (!batch.isEmpty()) {
            analyzeBatch(privacyService, batch, spansByText, resultBudget);
        }

        return Map.copyOf(spansByText);
    }

    private static void analyzeBatch(
            PrivacyService privacyService,
            List<String> batch,
            Map<String, List<PiiSpan>> spansByText,
            AnalysisResultBudget resultBudget
    ) {
        List<String> immutableBatch = List.copyOf(batch);
        List<List<ResolvedPiiSpan>> resolvedSpansByText =
                privacyService.analyzeSegments(immutableBatch);
        for (int index = 0; index < immutableBatch.size(); index++) {
            List<ResolvedPiiSpan> resolvedSpans = resolvedSpansByText.get(index);
            resultBudget.accept(resolvedSpans.size());
            spansByText.put(
                    immutableBatch.get(index),
                    resolvedSpans.stream()
                            .map(PrivacyJsonScalarBatchAnalyzer::toPiiSpan)
                            .toList()
            );
        }
    }

    private static PiiSpan toPiiSpan(ResolvedPiiSpan span) {
        double score = span.evidence().stream()
                .mapToDouble(evidence -> evidence.score())
                .max()
                .orElseThrow();
        return new PiiSpan(span.entityType(), span.start(), span.end(), score);
    }

    private static final class AnalysisResultBudget {

        private final PrivacyPhase phase;
        private final int maxResultSpans;
        private int spanCount;

        private AnalysisResultBudget(PrivacyPhase phase, int maxResultSpans) {
            this.phase = phase;
            this.maxResultSpans = maxResultSpans;
        }

        private void accept(int additionalSpans) {
            long updatedSpanCount = (long) this.spanCount + additionalSpans;
            PrivacyJsonPayloadTransformer.requireWithinLimit(
                    updatedSpanCount,
                    this.maxResultSpans,
                    this.phase
            );
            this.spanCount = (int) updatedSpanCount;
        }
    }
}
