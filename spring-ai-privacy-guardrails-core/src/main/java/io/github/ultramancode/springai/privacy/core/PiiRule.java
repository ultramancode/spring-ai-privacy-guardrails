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

    /** Country metadata, such as {@code KR}. Selection uses rule IDs and preset names. */
    default Set<String> countries() {
        return Set.of();
    }

    /**
     * Finds spans in the original text using UTF-16 offsets, inclusive start and exclusive end.
     * Return an empty list for no matches. Do not retain the text. Respect the
     * supplied result budget and interruption. Exceptions fail the local group.
     */
    List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits);
}
