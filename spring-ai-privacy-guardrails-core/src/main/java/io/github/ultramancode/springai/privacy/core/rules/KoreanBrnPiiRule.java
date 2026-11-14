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
 * Hyphenated Korean business registration number candidates checked with the check digit.
 * A candidate followed by a hyphen and a digit is skipped because it is the start of a
 * longer number, such as an ISBN.
 */
final class KoreanBrnPiiRule implements PiiRule {
    private static final String BRN_EXPRESSION =
            "(?<![A-Za-z0-9])[0-9]{3}-[0-9]{2}-[0-9]{5}(?![A-Za-z0-9])(?!-[0-9])";
    private static final PatternDefinition BRN_PATTERN = PatternDefinition.builder()
        .expression(BRN_EXPRESSION)
        .score(0.85)
        .validator(KoreanBrnPiiRule::isValidCandidate)
        .build();
    private static final int[] CHECK_DIGIT_WEIGHTS = {1, 3, 7, 1, 3, 7, 1, 3, 5};

    private final PatternPiiRule patternRule = PatternPiiRule.builder()
        .id(BuiltInPiiRuleIds.KR_BRN)
        .entityType(PiiEntityTypes.KR_BRN)
        .addPattern(BRN_PATTERN)
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

    /** Validates the tenth digit, the check digit, against the value computed from the first nine digits. */
    private static boolean isValidCandidate(String candidate) {
        String digits = candidate.replace("-", "");

        // Multiply each of the first nine digits by its weight and add the products.
        int sum = 0;
        for (int i = 0; i < CHECK_DIGIT_WEIGHTS.length; i++) {
            sum += (digits.charAt(i) - '0') * CHECK_DIGIT_WEIGHTS[i];
        }

        // The ninth product also contributes its tens digit.
        int ninthProduct = (digits.charAt(8) - '0') * CHECK_DIGIT_WEIGHTS[8];
        sum += ninthProduct / 10;

        // The check digit is 10 minus the last digit of the sum, or 0 when that last digit is 0.
        int checkDigit = (10 - sum % 10) % 10;
        return checkDigit == digits.charAt(9) - '0';
    }
}
