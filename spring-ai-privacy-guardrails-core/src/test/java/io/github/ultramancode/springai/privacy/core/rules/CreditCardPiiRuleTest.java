package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreditCardPiiRuleTest {
    private final CreditCardPiiRule rule = new CreditCardPiiRule();

    @ParameterizedTest
    @ValueSource(strings = {
        "122000000000003", "371449635398431", "5555555555554444", "5019717010103742",
        "30569309025904", "6011000400000000", "3528000700000000", "6759649826438453",
        "4111111111111111", "4917300800000000", "4484070000000000",
        "4111 1111 1111 1111", "4111-1111-1111-1111", "3782 822463 10005"
    })
    void detectsSupportedFormsAtOriginalUtf16Positions(String number) {
        String text = "한글😀 " + number + " 연락";

        assertThat(analyze(text))
            .containsExactly(new PiiSpan("CREDIT_CARD", 5, 5 + number.length(), 0.85));
    }

    @Test
    void detectsMultipleCardsSeparatedBySpaces() {
        String text = "4012888888881881 4012-8888-8888-1881 4012 8888 8888 1881";

        assertThat(analyze(text)).containsExactly(
            new PiiSpan("CREDIT_CARD", 0, 16, 0.85),
            new PiiSpan("CREDIT_CARD", 17, 36, 0.85),
            new PiiSpan("CREDIT_CARD", 37, 56, 0.85));
    }

    @ParameterizedTest
    @CsvSource({
        "2026 4111111111111111, 4111111111111111",
        "4111111111111111 123, 4111111111111111",
        "4111 1111 1111 1111 1111, 4111 1111 1111 1111",
        "99999-4111-1111-1111-1111, 4111-1111-1111-1111"
    })
    void detectsCardsAtWordBoundariesNextToOtherNumbers(String text, String number) {
        int start = text.indexOf(number);

        assertThat(analyze(text))
            .containsExactly(new PiiSpan("CREDIT_CARD", start, start + number.length(), 0.85));
    }

    @Test
    void detectsCardNumbersWithoutIncludingTheFollowingExpiryDate() {
        for (String number : List.of("4222222222222", "3782 822463 10005", "4111111111111111",
            "4111 1111 1111 1111", "4111-1111-1111-1111")) {
            for (String expiry : List.of("12/26", "01/2027")) {
                String text = "한글😀 " + number + " " + expiry;

                assertThat(analyze(text)).as(text)
                    .containsExactly(new PiiSpan("CREDIT_CARD", 5, 5 + number.length(), 0.85));
            }
        }
    }

    @Test
    void rejectsMalformedCandidates() {
        for (String text : List.of("4111 1111 1111 1112", "4111 1111 1111 1112 12/26",
            "4012-8888-8888-1882", "my credit card number is 4012-8888-8888-1882",
            "36168002586008", "my credit card number is 36168002586008",
            "0000000000000000", "41111111111111111111", "41111111111111111111 12/26")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void ignoresTimestampsAndNumbersOutsideTheSupportedFormat() {
        for (String text : List.of("1748503543012", "4000000000000000006",
            "4000 0000 0000 0000 006", "2223000048400011", "A4111111111111111",
            "4111111111111111Z", "카드4111111111111111")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    private List<PiiSpan> analyze(String text) {
        return this.rule.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
