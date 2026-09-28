package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Provider contract for detecting PII spans in text. */
@FunctionalInterface
public interface PiiAnalyzer {

    /**
     * Analyzes source text without retaining or mutating it. Implementations are
     * shared by {@link PrivacyService} and must therefore be thread-safe and
     * reentrant.
     * Blocking implementations must set a finite deadline and cooperate
     * with thread interruption.
     *
     * <p>Successful results may be reused for identical input within a
     * {@link PrivacySession}. Detection models, rules, and other settings that
     * affect results must remain stable throughout that session.</p>
     *
     * <p>If adding another span would exceed the supplied
     * {@link PrivacyProcessingLimits#maxResultSpans() maxResultSpans} limit,
     * implementations must throw a {@link PrivacyGuardrailException} with
     * failure code {@link PrivacyFailureCode#PAYLOAD_LIMIT_EXCEEDED}.
     * {@link PrivacyService} also enforces the total span limit across analyzers.</p>
     *
     * @param text non-null source text. The core service does not invoke analyzers for null or blank input
     * @param options non-null validated analysis options
     * @param limits non-null processing limits for this analysis
     * @return a non-null list containing only non-null spans whose ranges are
     * within the source text
     */
    List<PiiSpan> analyze(
            String text,
            PiiAnalysisOptions options,
            PrivacyProcessingLimits limits
    );

    /**
     * Analyzes independent source texts in one batch. Results must follow input
     * order, with offsets measured from the start of each source text. Each text
     * must follow the same detection semantics as
     * {@link #analyze(String, PiiAnalysisOptions, PrivacyProcessingLimits)},
     * independently of other texts in the batch.
     * Implementations that override this method must not modify the input list
     * or keep references to the list or its texts after the method returns.
     *
     * <p>The default implementation analyzes each text with the supplied limits
     * and copies the returned span list before analyzing the next text.</p>
     *
     * <p>{@link PrivacyService} passes only non-null, non-blank texts within the
     * configured {@link PrivacyProcessingLimits#maxAnalysisSegments() maxAnalysisSegments}
     * and combined {@link PrivacyProcessingLimits#maxTextCharacters() maxTextCharacters}
     * limits. It validates the {@link PrivacyProcessingLimits#maxResultSpans() maxResultSpans}
     * limit on the returned results.</p>
     *
     * @param texts non-null list of independent source texts with no null elements
     * @param options non-null validated analysis options shared by the texts
     * @param limits non-null processing limits for this batch
     * @return a non-null result list with exactly one non-null span list per text
     * @throws PrivacyGuardrailException if the segment limit is exceeded or another
     * detected span would exceed the batch span limit. The failure code is
     * {@link PrivacyFailureCode#PAYLOAD_LIMIT_EXCEEDED}
     */
    default List<List<PiiSpan>> analyzeSegments(
            List<String> texts,
            PiiAnalysisOptions options,
            PrivacyProcessingLimits limits
    ) {
        Objects.requireNonNull(texts, "texts must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        if (texts.size() > limits.maxAnalysisSegments()) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                    PrivacyPhase.ANALYSIS,
                    "PII analysis exceeded the configured segment limit"
            );
        }
        List<List<PiiSpan>> results = new ArrayList<>(texts.size());
        int spanCount = 0;
        for (String text : texts) {
            if (Thread.currentThread().isInterrupted()) {
                throw new PrivacyGuardrailException(
                        PrivacyFailureCode.ANALYSIS_INTERRUPTED,
                        PrivacyPhase.ANALYSIS,
                        "PII analysis interrupted"
                );
            }
            List<PiiSpan> spans = analyze(
                    Objects.requireNonNull(text, "texts must not contain null values"),
                    options,
                    limits
            );
            if (spans == null) {
                throw new PrivacyGuardrailException(
                        PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION,
                        PrivacyPhase.ANALYSIS,
                        "PII analyzer returned a null segmented result"
                );
            }
            if (spans.size() > limits.maxResultSpans() - spanCount) {
                throw new PrivacyGuardrailException(
                        PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED,
                        PrivacyPhase.ANALYSIS,
                        "PII analyzer segmented result exceeded the configured span limit"
                );
            }
            List<PiiSpan> snapshot = new ArrayList<>(spans);
            spanCount += spans.size();
            results.add(snapshot);
        }
        return List.copyOf(results);
    }

    /**
     * Returns the stable provider ID used by resolution policies and diagnostics.
     * Provider IDs are 1-to-128-character ASCII identifiers composed of
     * alphanumeric segments separated by single hyphens or underscores. ASCII
     * letter case is insignificant and core exposes the canonical uppercase form.
     * Every configured analyzer must expose a distinct provider ID, so at most
     * one custom analyzer may retain the default.
     *
     * @return stable provider ID. Custom analyzers default to {@code CUSTOM}
     */
    default String providerId() {
        return "CUSTOM";
    }

    /**
     * Returns exact uppercase canonical entity types that this locally configured
     * analyzer is trusted to emit.
     * Remote or otherwise untrusted analyzers must keep the default empty set.
     *
     * @return non-null canonical entity types trusted from this analyzer
     */
    default Set<String> trustedEntityTypes() {
        return Set.of();
    }
}
