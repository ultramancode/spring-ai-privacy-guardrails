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

/** Card number candidates checked with Luhn validation. */
final class CreditCardPiiRule implements PiiRule {
    private static final String CARD_NUMBER_EXPRESSION =
            "(?U)\\b(?!1[0-9]{12}(?![0-9]))"
                    + "(?:4[0-9]{3}|5[0-5][0-9]{2}|6[0-9]{3}|1[0-9]{3}|3[0-9]{3})"
                    + "[- ]?[0-9]{3,4}[- ]?[0-9]{3,4}[- ]?[0-9]{3,5}\\b";
    private static final PatternDefinition CARD_NUMBER_PATTERN = PatternDefinition.builder()
            .expression(CARD_NUMBER_EXPRESSION)
            .score(0.85)
            .validator(CreditCardPiiRule::isValidCandidate)
            .build();

    private final PatternPiiRule patternRule = PatternPiiRule.builder()
            .id(BuiltInPiiRuleIds.CREDIT_CARD)
            .entityType(PiiEntityTypes.CREDIT_CARD)
            .addPattern(CARD_NUMBER_PATTERN)
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

    /** Validates card-number candidates using the Luhn checksum. */
    private static boolean isValidCandidate(String candidate) {
        String digits = candidate.replace(" ", "").replace("-", "");
        return hasValidLuhnChecksum(digits);
    }

    private static boolean hasValidLuhnChecksum(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
    }
}
