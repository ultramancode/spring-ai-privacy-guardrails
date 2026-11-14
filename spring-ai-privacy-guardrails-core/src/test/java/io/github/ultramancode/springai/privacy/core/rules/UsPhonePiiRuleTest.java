package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UsPhonePiiRuleTest {
    private final UsPhonePiiRule rule = new UsPhonePiiRule();

    @Test
    void detectsSupportedFormsAtOriginalUtf16Positions() {
        for (String number : List.of("(212) 555-1234", "(212)555-1234", "212-555-1234", "212.555.1234",
            "212 555 1234", "+1 212-555-1234", "+1-212-555-1234", "+1.212.555.1234", "+1 212 555 1234",
            "+1 (212) 555-1234", "+12125551234", "1-800-555-0199", "1.212.555.1234", "1 212 555 1234")) {
            // The emoji takes two UTF-16 units, so the match starts at 5.
            String text = "Hi😀 " + number + " today";

            assertThat(analyze(text)).as(number)
                .containsExactly(new PiiSpan("PHONE_NUMBER", 5, 5 + number.length(), 0.85));
        }
    }

    @Test
    void detectsNumberBeforeAHyphenatedExtension() {
        assertThat(analyze("212-555-1234-123"))
            .containsExactly(new PiiSpan("PHONE_NUMBER", 0, 12, 0.85));
    }

    @Test
    void rejectsInvalidAreaAndExchangeCodes() {
        for (String text : List.of(
            "211-555-1234", "911-555-1234", // Service codes such as 911 are not area codes.
            "290-555-1234", // Area codes with 9 in the middle are reserved.
            "212-411-1234", // Service codes are not exchange codes either.
            "012-555-1234", "112-555-1234", "212-055-1234", "212-155-1234")) { // Neither code starts with 0 or 1.
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void rejectsDigitsRightAfterAPlusSign() {
        // +212 is a country code here, so 212 is not a US area code.
        assertThat(analyze("+212-555-1234")).isEmpty();
    }

    @Test
    void rejectsOtherForms() {
        for (String text : List.of("2125551234", "12125551234", "212-555.1234", "212 555-1234", "212-555-12345",
            "212-5555-1234", "x212-555-1234", "212-555-1234x")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    private List<PiiSpan> analyze(String text) {
        return this.rule.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
