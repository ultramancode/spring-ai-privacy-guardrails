package io.github.ultramancode.springai.privacy.core;

import java.util.List;
import java.util.Set;

/**
 * One selectable local detection rule. Implementations must be thread-safe and
 * stable for the lifetime of an analyzer that uses them.
 * Use {@link PiiAnalyzer} for independent remote services or model providers.
 */
public interface PiiRule {
    /** Selection ID of 1-64 lowercase ASCII letters or digits, separated by single hyphens. */
    String id();

    /** Nonempty set of canonical entity types this rule can emit. */
    Set<String> entityTypes();

    /**
     * Finds spans in the original text using UTF-16 offsets, inclusive start and exclusive end.
     * Return an empty list for no matches. Do not retain the text. Cooperate with
     * thread interruption. A rule failure fails the entire {@code RULES} analysis call.
     *
     * <p>If another span would exceed {@link PrivacyProcessingLimits#maxResultSpans()},
     * implementations must throw {@link PrivacyGuardrailException} with code
     * {@link PrivacyFailureCode#PAYLOAD_LIMIT_EXCEEDED} and phase {@link PrivacyPhase#ANALYSIS}.
     * Processing limit failures abort the operation regardless of provider failure policy.</p>
     *
     * @return a non-null list of non-null spans with types declared by {@link #entityTypes()}
     */
    List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits);
}
