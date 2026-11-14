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
 * US Individual Taxpayer Identification Number candidates with hyphens or spaces between the groups,
 * checked for an ITIN group number. A candidate followed by a hyphen and a digit is skipped because
 * it is the start of a longer number, such as an ISBN.
 */
final class UsItinPiiRule implements PiiRule {
    // \1 repeats the first separator, so both separators are the same.
    private static final String ITIN_EXPRESSION =
            "(?<![A-Za-z0-9])9[0-9]{2}([- ])[0-9]{2}\\1[0-9]{4}(?![A-Za-z0-9])(?!-[0-9])";
    private static final PatternDefinition ITIN_PATTERN = PatternDefinition.builder()
        .expression(ITIN_EXPRESSION)
        .score(0.85)
        .validator(UsItinPiiRule::isValidCandidate)
        .build();

    private final PatternPiiRule patternRule = PatternPiiRule.builder()
        .id(BuiltInPiiRuleIds.US_ITIN)
        .entityType(PiiEntityTypes.US_ITIN)
        .addPattern(ITIN_PATTERN)
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

    /** Validates the group number, the fourth and fifth digits: 50-65, 70-88, 90-92, or 94-99. */
    private static boolean isValidCandidate(String candidate) {
        int group = Integer.parseInt(candidate.substring(4, 6));
        return (group >= 50 && group <= 65)
            || (group >= 70 && group <= 88)
            || (group >= 90 && group <= 92)
            || (group >= 94 && group <= 99);
    }
}
