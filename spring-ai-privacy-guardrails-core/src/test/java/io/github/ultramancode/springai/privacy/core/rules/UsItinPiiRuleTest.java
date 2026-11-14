package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UsItinPiiRuleTest {
    private final UsItinPiiRule rule = new UsItinPiiRule();

    @Test
    void detectsSeparatedNumbersAtOriginalUtf16Positions() {
        // The first and last group numbers of each ITIN range, and one space-separated number.
        for (String number : List.of("900-50-1234", "999-65-1234", "912-70-1234", "912 70 1234", "912-88-1234",
            "912-90-1234", "912-92-1234", "912-94-1234", "912-99-1234")) {
            // The emoji takes two UTF-16 units, so the match starts at 5.
            String text = "Hi😀 " + number + " today";

            assertThat(analyze(text)).as(number)
                .containsExactly(new PiiSpan("US_ITIN", 5, 5 + number.length(), 0.85));
        }
    }

    @Test
    void rejectsNumbersOutsideTheItinRanges() {
        for (String text : List.of("912-00-1234", "912-49-1234", "912-66-1234", "912-69-1234",
            "912-89-1234", "912-93-1234", "812-70-1234")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void rejectsNumbersFollowedByAHyphenAndADigit() {
        // 978-84-9838 has an ITIN group number, but here it is the start of an ISBN.
        assertThat(analyze("978-84-9838")).hasSize(1);
        for (String text : List.of("978-84-9838-123-4", "912-70-1234-5")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void rejectsOtherForms() {
        for (String text : List.of("912701234", "912-70 1234", "912.70.1234", "x912-70-1234", "912-70-1234x",
            "1912-70-1234", "912-70-12345")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    private List<PiiSpan> analyze(String text) {
        return this.rule.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
