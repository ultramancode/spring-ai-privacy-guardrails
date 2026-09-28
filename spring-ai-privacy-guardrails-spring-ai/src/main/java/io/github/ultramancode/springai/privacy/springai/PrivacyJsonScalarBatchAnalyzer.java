package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.ResolvedPiiSpan;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Projects session analysis of JSON texts into spans used by scalar actions. */
final class PrivacyJsonScalarBatchAnalyzer {

    private PrivacyJsonScalarBatchAnalyzer() {
    }

    static Map<String, List<PiiSpan>> analyze(
            PrivacyService privacyService,
            PrivacyContextHandle handle,
            List<String> analysisTexts
    ) {
        Objects.requireNonNull(privacyService, "privacyService must not be null");
        Objects.requireNonNull(analysisTexts, "analysisTexts must not be null");

        Map<String, List<PiiSpan>> spansByText = new LinkedHashMap<>();
        List<List<ResolvedPiiSpan>> resolvedSpansByText =
                privacyService.analyzeScalarTexts(handle, analysisTexts);
        for (int index = 0; index < analysisTexts.size(); index++) {
            List<ResolvedPiiSpan> resolvedSpans = resolvedSpansByText.get(index);
            spansByText.put(
                    analysisTexts.get(index),
                    resolvedSpans.stream()
                            .map(PrivacyJsonScalarBatchAnalyzer::toPiiSpan)
                            .toList()
            );
        }
        return Map.copyOf(spansByText);
    }

    private static PiiSpan toPiiSpan(ResolvedPiiSpan span) {
        double score = span.evidence().stream()
                .mapToDouble(evidence -> evidence.score())
                .max()
                .orElseThrow();
        return new PiiSpan(span.entityType(), span.start(), span.end(), score);
    }

}
