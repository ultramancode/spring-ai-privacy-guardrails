package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiEntityTypes;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Korean foreigner registration number candidates checked for a valid birth date. */
final class KoreanFrnPiiRule implements PiiRule {
    private static final String FRN_EXPRESSION =
            "(?<![A-Za-z0-9])[0-9]{6}-?[5-8][0-9]{6}(?![A-Za-z0-9])";
    private static final PatternDefinition FRN_PATTERN = PatternDefinition.builder()
        .expression(FRN_EXPRESSION)
        .score(0.85)
        .validator(KoreanFrnPiiRule::isValidCandidate)
        .build();

    private final PatternPiiRule patternRule = PatternPiiRule.builder()
        .id(BuiltInPiiRuleIds.KR_FRN)
        .entityType(PiiEntityTypes.KR_FRN)
        .addPattern(FRN_PATTERN)
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
     * Validates the birth date, including leap years, using {@link LocalDate}.
     * The seventh digit indicates the century: 5 or 6 for the 1900s, 7 or 8 for the 2000s.
     */
    private static boolean isValidCandidate(String candidate) {
        String digits = candidate.replace("-", "");
        char centuryDigit = digits.charAt(6);
        int century = centuryDigit == '5' || centuryDigit == '6' ? 1900 : 2000;
        int year = century + Integer.parseInt(digits.substring(0, 2));
        int month = Integer.parseInt(digits.substring(2, 4));
        int day = Integer.parseInt(digits.substring(4, 6));
        try {
            // LocalDate.of throws for a date that does not exist, such as February 30.
            LocalDate.of(year, month, day);
            return true;
        } catch (DateTimeException invalidDate) {
            return false;
        }
    }
}
