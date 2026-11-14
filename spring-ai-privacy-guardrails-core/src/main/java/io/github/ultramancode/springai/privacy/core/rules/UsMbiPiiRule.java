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
 * US Medicare Beneficiary Identifiers, compact or with hyphens after the fourth and seventh
 * characters. Each of the 11 positions only accepts the characters that CMS assigns there.
 * Letters may be in either case.
 */
final class UsMbiPiiRule implements PiiRule {
    // Letters without S, L, O, I, B, and Z. The expression ignores case.
    private static final String LETTER = "[AC-HJKMNP-RT-Y]";
    private static final String LETTER_OR_DIGIT = "[0-9AC-HJKMNP-RT-Y]";
    // The three parts follow the CMS layout C A AN N, A AN N, A A N N, where C is 1-9, N is 0-9,
    // A is a letter, and AN is a letter or a digit.
    private static final String FIRST_PART = "[1-9]" + LETTER + LETTER_OR_DIGIT + "[0-9]";
    private static final String SECOND_PART = LETTER + LETTER_OR_DIGIT + "[0-9]";
    private static final String THIRD_PART = LETTER + LETTER + "[0-9]{2}";
    private static final String MBI_EXPRESSION =
            "(?i)(?<![A-Za-z0-9])" + FIRST_PART
        + "(?:" + SECOND_PART + THIRD_PART + "|-" + SECOND_PART + "-" + THIRD_PART + ")"
        + "(?![A-Za-z0-9])";
    private static final PatternDefinition MBI_PATTERN = PatternDefinition.builder()
        .expression(MBI_EXPRESSION)
        .score(0.85)
        .build();

    private final PatternPiiRule patternRule = PatternPiiRule.builder()
        .id(BuiltInPiiRuleIds.US_MBI)
        .entityType(PiiEntityTypes.US_MBI)
        .addPattern(MBI_PATTERN)
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
}
