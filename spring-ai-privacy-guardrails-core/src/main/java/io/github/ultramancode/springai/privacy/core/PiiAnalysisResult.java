package io.github.ultramancode.springai.privacy.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Resolved PII spans together with successful providers and sanitized failures.
 *
 * @param spans immutable, source-ordered, non-overlapping resolved spans
 * @param successfulProviders canonical provider IDs whose analysis completed successfully
 * @param failures sanitized failures from providers that did not complete successfully
 */
public record PiiAnalysisResult(
        List<ResolvedPiiSpan> spans,
        Set<String> successfulProviders,
        List<PiiAnalyzerFailure> failures
) {

    /** Validates provider consistency and resolved-span ordering. */
    public PiiAnalysisResult {
        spans = List.copyOf(Objects.requireNonNull(spans, "spans must not be null"));
        Set<String> canonicalSuccessfulProviders = new LinkedHashSet<>();
        for (String provider : Objects.requireNonNull(
                successfulProviders,
                "successfulProviders must not be null"
        )) {
            if (!canonicalSuccessfulProviders.add(PiiProviderId.canonicalize(provider))) {
                throw new IllegalArgumentException("successfulProviders contain canonical duplicates");
            }
        }
        successfulProviders = Set.copyOf(canonicalSuccessfulProviders);
        failures = List.copyOf(Objects.requireNonNull(failures, "failures must not be null"));

        int previousEnd = -1;
        for (ResolvedPiiSpan span : spans) {
            if (span.start() < previousEnd) {
                throw new IllegalArgumentException("spans must be ordered and non-overlapping");
            }
            previousEnd = span.end();
            for (PiiEvidence item : span.evidence()) {
                if (!successfulProviders.contains(item.provider())) {
                    throw new IllegalArgumentException("span evidence provider was not successful");
                }
            }
        }

        Set<String> failedProviders = new LinkedHashSet<>();
        for (PiiAnalyzerFailure failure : failures) {
            if (!failedProviders.add(failure.provider())) {
                throw new IllegalArgumentException("failures contain duplicate providers");
            }
            if (successfulProviders.contains(failure.provider())) {
                throw new IllegalArgumentException("a provider cannot be both successful and failed");
            }
        }
    }

    @Override
    public String toString() {
        return "PiiAnalysisResult[spanCount=" + this.spans.size()
                + ", successfulProviders=" + this.successfulProviders
                + ", failures=" + this.failures + "]";
    }
}
