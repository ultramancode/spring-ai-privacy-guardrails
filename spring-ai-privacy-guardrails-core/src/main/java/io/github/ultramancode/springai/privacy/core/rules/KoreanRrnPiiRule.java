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
            .entityType(PiiEntityTypes.KOREAN_RRN)
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
        int century = digits.charAt(6) == '1' || digits.charAt(6) == '2' ? 1900 : 2000;
        try {
            LocalDate.of(century + Integer.parseInt(digits.substring(0, 2)),
                    Integer.parseInt(digits.substring(2, 4)), Integer.parseInt(digits.substring(4, 6)));
            return true;
        } catch (DateTimeException invalidDate) {
            return false;
        }
    }
}
