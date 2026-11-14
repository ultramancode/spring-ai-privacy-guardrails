package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiEntityTypes;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;

import java.util.List;
import java.util.Set;

/**
 * US Social Security number candidates with hyphens or spaces between the groups, checked against
 * the numbers the SSA does not assign.
 */
final class UsSsnPiiRule implements PiiRule {
    // \1 repeats the first separator, so both separators are the same.
    private static final String SSN_EXPRESSION =
            "(?<![A-Za-z0-9])[0-9]{3}([- ])[0-9]{2}\\1[0-9]{4}(?![A-Za-z0-9])";
    private static final PatternDefinition SSN_PATTERN = PatternDefinition.builder()
        .expression(SSN_EXPRESSION)
        .score(0.85)
        .validator(UsSsnPiiRule::isValidCandidate)
        .build();

    private final PatternPiiRule patternRule = PatternPiiRule.builder()
        .id(BuiltInPiiRuleIds.US_SSN)
        .entityType(PiiEntityTypes.US_SSN)
        .addPattern(SSN_PATTERN)
        .build();

    @Override
    public String id() {
        return this.patternRule.id();
    }

    @Override
    public Set<String> entityTypes() {
        return this.patternRule.entityTypes();
    }

    @Override
    public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
        return this.patternRule.analyze(text, options, limits);
    }

    /**
     * Rejects area numbers (the first three digits) 000, 666, and 900 through 999, group number
     * (the middle two) 00, and serial number (the last four) 0000. Area numbers from 900 are used
     * for ITINs instead.
     */
    private static boolean isValidCandidate(String candidate) {
        int area = Integer.parseInt(candidate.substring(0, 3));
        int group = Integer.parseInt(candidate.substring(4, 6));
        int serial = Integer.parseInt(candidate.substring(7, 11));
        return area != 0 && area != 666 && area < 900 && group != 0 && serial != 0;
    }
}
