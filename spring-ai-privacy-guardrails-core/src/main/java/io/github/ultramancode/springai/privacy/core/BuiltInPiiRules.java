package io.github.ultramancode.springai.privacy.core;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/** Rule definitions for the COMMON and KR presets. */
final class BuiltInPiiRules {
    private BuiltInPiiRules() {
    }

    static List<PiiRule> rules() {
        return List.of(
                new PatternPiiRule("email", "EMAIL_ADDRESS",
                        "(?<![A-Za-z0-9_.+%@-])[A-Za-z0-9_.+%-]{1,64}"
                                + "@(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\\.){1,126}"
                                + "[A-Za-z]{2,63}(?![A-Za-z0-9_@-])",
                        0.85, 0, candidate -> candidate.length() <= 254 && !candidate.startsWith(".")
                                && !candidate.contains("..") && !candidate.contains(".@")),
                new PatternPiiRule("credit-card", "CREDIT_CARD",
                        "(?<![A-Za-z0-9])(?<![0-9][ -])(?:[0-9][ -]?){12,18}[0-9](?![A-Za-z0-9]|[ -][0-9])",
                        0.85, 0, BuiltInPiiRules::isLuhnNumber),
                new KoreanMobilePiiRule(),
                new PatternPiiRule("kr-rrn", "KOREAN_RRN", List.of(new PatternPiiRule.Pattern(
                        "(?<![A-Za-z0-9])[0-9]{6}-?[1-4][0-9]{6}(?![A-Za-z0-9])",
                        0.85, 0, BuiltInPiiRules::hasResidentBirthDate)), Set.of("KR"))
        );
    }

    static List<PiiRulePreset> presets() {
        return List.of(new PiiRulePreset("COMMON", List.of("email", "credit-card")),
                new PiiRulePreset("KR", List.of("kr-mobile-phone", "kr-rrn")));
    }

    private static boolean isLuhnNumber(String candidate) {
        String digits = candidate.replace(" ", "").replace("-", "");
        if (digits.chars().distinct().count() == 1) {
            return false;
        }
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

    // Randomly assigned trailing digits make calendar validity the applicable check.
    private static boolean hasResidentBirthDate(String candidate) {
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
