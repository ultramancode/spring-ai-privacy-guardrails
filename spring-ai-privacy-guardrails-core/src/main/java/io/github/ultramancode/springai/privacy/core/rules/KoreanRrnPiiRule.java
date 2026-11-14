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

/** Korean resident registration number candidates checked for a valid birth date. */
final class KoreanRrnPiiRule implements PiiRule {
    private static final String RRN_EXPRESSION =
            "(?<![A-Za-z0-9])[0-9]{6}-?[1-4][0-9]{6}(?![A-Za-z0-9])";
    private static final PatternDefinition RRN_PATTERN = PatternDefinition.builder()
        .expression(RRN_EXPRESSION)
        .score(0.85)
        .validator(KoreanRrnPiiRule::isValidCandidate)
        .build();

    private final PatternPiiRule patternRule = PatternPiiRule.builder()
        .id(BuiltInPiiRuleIds.KR_RRN)
        .entityType(PiiEntityTypes.KR_RRN)
        .addPattern(RRN_PATTERN)
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
     * The seventh digit indicates the century: 1 or 2 for the 1900s, 3 or 4 for the 2000s.
     */
    private static boolean isValidCandidate(String candidate) {
        String digits = candidate.replace("-", "");
        char centuryDigit = digits.charAt(6);
        int century = centuryDigit == '1' || centuryDigit == '2' ? 1900 : 2000;
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
