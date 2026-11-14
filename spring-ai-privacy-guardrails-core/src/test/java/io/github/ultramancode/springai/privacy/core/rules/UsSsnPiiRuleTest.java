package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UsSsnPiiRuleTest {
    private final UsSsnPiiRule rule = new UsSsnPiiRule();

    @Test
    void detectsSeparatedNumbersAtOriginalUtf16Positions() {
        for (String number : List.of("123-45-6789", "123 45 6789", "001-01-0001", "665-99-9999", "667-12-3456",
            "899-99-9999")) {
            // The emoji takes two UTF-16 units, so the match starts at 5.
            String text = "Hi😀 " + number + " today";

            assertThat(analyze(text)).as(number)
                .containsExactly(new PiiSpan("US_SSN", 5, 5 + number.length(), 0.85));
        }
    }

    @Test
    void detectsNumbersNextToPunctuation() {
        for (String text : List.of("(123-45-6789)", "SSN: 123-45-6789.")) {
            assertThat(analyze(text)).as(text).hasSize(1);
        }
    }

    @Test
    void rejectsNumbersTheSsaDoesNotAssign() {
        for (String text : List.of("000-12-3456", "666-12-3456", "900-12-3456", "999-12-3456",
            "123-00-4567", "123-45-0000")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void rejectsOtherForms() {
        for (String text : List.of("123456789", "123-45 6789", "123 45-6789", "123.45.6789", "12-345-6789",
            "123-456-789", "x123-45-6789", "123-45-6789x", "1123-45-6789", "123-45-67890")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    private List<PiiSpan> analyze(String text) {
        return this.rule.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
