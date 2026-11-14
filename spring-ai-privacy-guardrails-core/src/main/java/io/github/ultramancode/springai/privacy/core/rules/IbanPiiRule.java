package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiEntityTypes;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * IBANs, compact or in groups of four separated by spaces or hyphens with one separator
 * throughout. A match must have its country's IBAN length and pass the check digits.
 * Letters may be in either case.
 */
final class IbanPiiRule implements PiiRule {
    // IBAN length by country code in the SWIFT IBAN Registry, as of release 103 (September 2026).
    private static final Map<String, Integer> IBAN_LENGTHS = Map.ofEntries(
        Map.entry("AD", 24), Map.entry("AE", 23), Map.entry("AL", 28), Map.entry("AT", 20), Map.entry("AZ", 28),
        Map.entry("BA", 20), Map.entry("BE", 16), Map.entry("BG", 22), Map.entry("BH", 22), Map.entry("BI", 27),
        Map.entry("BR", 29), Map.entry("BY", 28), Map.entry("CH", 21), Map.entry("CR", 22), Map.entry("CY", 28),
        Map.entry("CZ", 24), Map.entry("DE", 22), Map.entry("DJ", 27), Map.entry("DK", 18), Map.entry("DO", 28),
        Map.entry("EE", 20), Map.entry("EG", 29), Map.entry("ES", 24), Map.entry("FI", 18), Map.entry("FK", 18),
        Map.entry("FO", 18), Map.entry("FR", 27), Map.entry("GB", 22), Map.entry("GE", 22), Map.entry("GI", 23),
        Map.entry("GL", 18), Map.entry("GR", 27), Map.entry("GT", 28), Map.entry("HN", 28), Map.entry("HR", 21),
        Map.entry("HU", 28), Map.entry("IE", 22), Map.entry("IL", 23), Map.entry("IQ", 23), Map.entry("IS", 26),
        Map.entry("IT", 27), Map.entry("JO", 30), Map.entry("KW", 30), Map.entry("KZ", 20), Map.entry("LB", 28),
        Map.entry("LC", 32), Map.entry("LI", 21), Map.entry("LT", 20), Map.entry("LU", 20), Map.entry("LV", 21),
        Map.entry("LY", 25), Map.entry("MC", 27), Map.entry("MD", 24), Map.entry("ME", 22), Map.entry("MK", 19),
        Map.entry("MN", 20), Map.entry("MR", 27), Map.entry("MT", 31), Map.entry("MU", 30), Map.entry("NI", 28),
        Map.entry("NL", 18), Map.entry("NO", 15), Map.entry("OM", 23), Map.entry("PK", 24), Map.entry("PL", 28),
        Map.entry("PS", 29), Map.entry("PT", 25), Map.entry("QA", 29), Map.entry("RO", 24), Map.entry("RS", 22),
        Map.entry("RU", 33), Map.entry("SA", 24), Map.entry("SC", 31), Map.entry("SD", 18), Map.entry("SE", 24),
        Map.entry("SI", 19), Map.entry("SK", 24), Map.entry("SM", 27), Map.entry("SO", 23), Map.entry("ST", 25),
        Map.entry("SV", 28), Map.entry("TL", 23), Map.entry("TN", 24), Map.entry("TR", 26), Map.entry("UA", 29),
        Map.entry("VA", 22), Map.entry("VG", 24), Map.entry("XK", 20), Map.entry("YE", 30)
    );
    private static final PatternDefinition IBAN_PATTERN = PatternDefinition.builder()
        .expression(ibanExpression())
        .score(0.85)
        .validator(IbanPiiRule::isValidCandidate)
        .build();

    private final PatternPiiRule patternRule = PatternPiiRule.builder()
        .id(BuiltInPiiRuleIds.IBAN)
        .entityType(PiiEntityTypes.IBAN_CODE)
        .addPattern(IBAN_PATTERN)
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
     * Builds one alternative per country so that a match has exactly that country's length.
     * A grouped IBAN followed by a short word, such as {@code EUR}, then still ends at its
     * last group.
     */
    private static String ibanExpression() {
        String countries = IBAN_LENGTHS.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "[0-9]{2}" + accountPart(entry.getValue() - 4))
            .collect(Collectors.joining("|"));
        // (?i) ignores case. The lookahead skips positions that cannot start an IBAN before any country is tried.
        return "(?i)(?<![A-Za-z0-9])(?=[A-Z]{2}[0-9]{2})(?:" + countries + ")(?![A-Za-z0-9])";
    }

    /** Matches the characters after the check digits, compact or in groups of four after spaces or hyphens. */
    private static String accountPart(int length) {
        return "(?:[A-Z0-9]{" + length + "}|" + grouped(length, " ") + "|" + grouped(length, "-") + ")";
    }

    /** Matches groups of four, and a shorter last group if needed, each after the same separator. */
    private static String grouped(int length, String separator) {
        String lastGroup = length % 4 == 0 ? "" : separator + "[A-Z0-9]{" + length % 4 + "}";
        return "(?:" + separator + "[A-Z0-9]{4}){" + length / 4 + "}" + lastGroup;
    }

    /**
     * Validates the check digits. They must be 02 through 98, and the IBAN with its first four
     * characters moved to the end, read as a number, must leave 1 when divided by 97.
     */
    private static boolean isValidCandidate(String candidate) {
        String iban = candidate.replace(" ", "").replace("-", "");
        int checkDigits = Integer.parseInt(iban.substring(2, 4));
        // The formula only produces 02 through 98, but 00, 01, and 99 can still leave 1 below.
        if (checkDigits < 2 || checkDigits > 98) {
            return false;
        }

        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        // The number can be too long for a long, so keep only the remainder while reading it.
        for (int i = 0; i < rearranged.length(); i++) {
            // Digits keep their value, and letters A through Z count as the two-digit numbers 10 through 35.
            int value = Character.digit(rearranged.charAt(i), 36);
            remainder = (remainder * (value < 10 ? 10 : 100) + value) % 97;
        }
        return remainder == 1;
    }
}
