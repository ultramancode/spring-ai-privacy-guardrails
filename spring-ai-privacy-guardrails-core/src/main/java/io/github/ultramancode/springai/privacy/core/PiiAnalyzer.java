package io.github.ultramancode.springai.privacy.core;

import java.util.List;
import java.util.Set;

/** Provider contract for detecting PII spans in text. */
@FunctionalInterface
public interface PiiAnalyzer {

    /** Hard maximum number of spans retained from one complete analysis operation. */
    int MAX_RESULT_SPANS = 100_000;

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
