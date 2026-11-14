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
 * North American Numbering Plan phone numbers in common US formats, checked for valid area and
 * exchange codes. Unseparated ten-digit numbers are matched only after {@code +1}.
 */
final class UsPhonePiiRule implements PiiRule {
    // An area code or an exchange code. Neither starts with 0 or 1.
    private static final String CODE = "[2-9][0-9]{2}";
    private static final String LINE = "[0-9]{4}";
    // Every form may follow +1, and the unseparated form must. The hyphen, dot, and space forms
    // may also follow a bare 1 with the same separator.
    private static final String PHONE_EXPRESSION =
            "(?<![A-Za-z0-9+])(?:" // Digits right after + are a country code, not an area code.
        + "(?:\\+1 ?)?\\(" + CODE + "\\) ?" + CODE + "-" + LINE // (212) 555-1234
        + "|(?:\\+1[ .-]?|1-)?" + CODE + "-" + CODE + "-" + LINE // 212-555-1234
        + "|(?:\\+1[ .-]?|1\\.)?" + CODE + "\\." + CODE + "\\." + LINE // 212.555.1234
        + "|(?:\\+1 ?|1 )?" + CODE + " " + CODE + " " + LINE // 212 555 1234
        + "|\\+1" + CODE + CODE + LINE // +12125551234
        + ")(?![A-Za-z0-9])";
    private static final PatternDefinition PHONE_PATTERN = PatternDefinition.builder()
        .expression(PHONE_EXPRESSION)
        .score(0.85)
        .validator(UsPhonePiiRule::isValidCandidate)
        .build();

    private final PatternPiiRule patternRule = PatternPiiRule.builder()
        .id(BuiltInPiiRuleIds.US_PHONE)
        .entityType(PiiEntityTypes.PHONE_NUMBER)
        .addPattern(PHONE_PATTERN)
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
     * Rejects service codes such as 911 as area or exchange codes, and area codes with 9 in the
     * middle, which are reserved for expanding the numbering plan.
     */
    private static boolean isValidCandidate(String candidate) {
        String digits = candidate.replaceAll("[^0-9]", "");
        // Drop the leading 1 written as +1 or 1.
        String number = digits.length() == 11 ? digits.substring(1) : digits;
        String areaCode = number.substring(0, 3);
        String exchangeCode = number.substring(3, 6);
        return !isServiceCode(areaCode) && areaCode.charAt(1) != '9' && !isServiceCode(exchangeCode);
    }

    private static boolean isServiceCode(String code) {
        return code.charAt(1) == '1' && code.charAt(2) == '1';
    }
}
