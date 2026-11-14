package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IbanPiiRuleTest {
    private final IbanPiiRule rule = new IbanPiiRule();

    @Test
    void detectsCompactAndGroupedIbansAtOriginalUtf16Positions() {
        // NO and RU have the shortest and longest IBAN lengths.
        for (String iban : List.of("DE89370400440532013000", "DE89 3704 0044 0532 0130 00",
            "DE89-3704-0044-0532-0130-00", "de89370400440532013000", "gb82 west 1234 5698 7654 32",
            "GB82 WEST 1234 5698 7654 32", "FR1420041010050500013M02606", "NO9386011117947",
            "MT84MALT011000012345MTLCAST001S", "RU0204452560040702810412345678901")) {
            // The emoji takes two UTF-16 units, so the match starts at 5.
            String text = "Hi😀 " + iban + " today";

            assertThat(analyze(text)).as(iban)
                .containsExactly(new PiiSpan("IBAN_CODE", 5, 5 + iban.length(), 0.85));
        }
    }

    @Test
    void endsGroupedIbansAtTheLengthOfTheirCountry() {
        // BE and PL IBANs end with a full group, so the next word has the shape of one more group.
        for (String iban : List.of("BE68 5390 0754 7034", "PL61 1090 1014 0000 0712 1981 2874")) {
            for (String suffix : List.of(" EUR", " 2024")) {
                String text = iban + suffix;

                assertThat(analyze(text)).as(text)
                    .containsExactly(new PiiSpan("IBAN_CODE", 0, iban.length(), 0.85));
            }
        }
    }

    @Test
    void rejectsInvalidCheckDigits() {
        // 01 and 99 pass the remainder check in place of 98 and 02, but valid check digits are 02 through 98.
        assertThat(analyze("DE98100000000000000010")).hasSize(1);
        assertThat(analyze("DE02100000000000000089")).hasSize(1);
        for (String text : List.of("DE89370400440532013001", "GB82 WEST 1234 5698 7654 33",
            "DE01100000000000000010", "DE99100000000000000089")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void rejectsLengthsAndCountriesOutsideTheRegistry() {
        // Each value has valid check digits, so only its length or country can reject it.
        for (String text : List.of("DE5137040044053201300", "DE543704004405320130001", "US64SVBKUS6S3300958879")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void rejectsOtherForms() {
        for (String text : List.of("DE89 3704-0044-0532-0130-00", "DE89-3704 0044 0532 0130 00",
            "DE89 37040044 0532 0130 00", "DE 89 3704 0044 0532 0130 00", "DE893704 0044 0532 0130 00",
            "xDE89370400440532013000", "DE89370400440532013000x")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    private List<PiiSpan> analyze(String text) {
        return this.rule.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
