package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Provider contract for detecting PII spans in text. */
@FunctionalInterface
public interface PiiAnalyzer {

    /** Hard maximum number of spans retained from one complete analysis operation. */
    int MAX_RESULT_SPANS = 100_000;

    /** Hard maximum number of independent texts accepted by one segmented analysis operation. */
    int MAX_ANALYSIS_SEGMENTS = 100_000;

    /**
     * Analyzes source text without retaining or mutating it. Implementations are
     * shared by {@link PrivacyService} and must therefore be thread-safe and
     * reentrant. A blocking implementation must apply its own finite deadline and
     * cooperate with thread interruption; request cancellation cannot forcibly stop
     * arbitrary synchronous analyzer code. Implementations must also bound their
     * work relative to the input and return at most {@link #MAX_RESULT_SPANS} spans.
     * The core validates this bound after the call, but cannot prevent a custom
     * analyzer from allocating an excessive result before it returns. Built-in
     * analyzers stop while collecting results so they do not first materialize an
     * oversized list.
     *
     * @param text non-null source text; the core service does not invoke analyzers for null or blank input
     * @param options non-null validated analysis options
     * @return a non-null list containing only non-null spans whose ranges are
     * within the source text
     */
    List<PiiSpan> analyze(String text, PiiAnalysisOptions options);

    /**
     * Analyzes independent source texts without allowing state retained while
     * analyzing one text, or offsets calculated for it, to affect another. The
     * result at each index belongs only to the source text at the same index. If
     * an analyzer processes several texts in one batch, it must return a separate
     * result list for each text in the same order, with offsets measured from the
     * start of that text. The default implementation calls
     * {@link #analyze(String, PiiAnalysisOptions)} once per text. Implementations
     * that override this method must not modify the input list or keep references
     * to the list or its texts after the method returns.
     *
     * <p>When invoked through {@link PrivacyService}, this method receives only
     * non-null, non-blank texts: at most {@link #MAX_ANALYSIS_SEGMENTS} items with
     * a combined length no greater than
     * {@link PrivacyService#MAX_TEXT_INPUT_CHARACTERS}. Implementations must return
     * no more than {@link #MAX_RESULT_SPANS} spans in total.</p>
     *
     * @param texts non-null, read-only independent source texts
     * @param options non-null validated analysis options shared by the texts
     * @return a non-null result list with exactly one non-null span list per text
     */
    default List<List<PiiSpan>> analyzeSegments(
            List<String> texts,
            PiiAnalysisOptions options
    ) {
        Objects.requireNonNull(texts, "texts must not be null");
        Objects.requireNonNull(options, "options must not be null");
        if (texts.size() > MAX_ANALYSIS_SEGMENTS) {
            throw new IllegalArgumentException("texts exceeded the safe segment limit");
        }

        List<List<PiiSpan>> results = new ArrayList<>(texts.size());
        long spanCount = 0L;
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
                    options
            );
            if (spans == null) {
                throw new PrivacyGuardrailException(
                        PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION,
                        PrivacyPhase.ANALYSIS,
                        "PII analyzer returned a null segmented result"
                );
            }
            long updatedSpanCount = spanCount + spans.size();
            if (updatedSpanCount > MAX_RESULT_SPANS) {
                throw new PrivacyGuardrailException(
                        PrivacyFailureCode.ANALYZER_CONTRACT_VIOLATION,
                        PrivacyPhase.ANALYSIS,
                        "PII analyzer segmented result exceeded the safe span limit"
                );
            }
            List<PiiSpan> snapshot = new ArrayList<>(spans);
            spanCount = updatedSpanCount;
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
     * @return stable provider ID; custom analyzers default to {@code CUSTOM}
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
