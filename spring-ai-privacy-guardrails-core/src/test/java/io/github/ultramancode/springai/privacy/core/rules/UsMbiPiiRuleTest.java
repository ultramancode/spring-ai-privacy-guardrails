package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UsMbiPiiRuleTest {
    private final UsMbiPiiRule rule = new UsMbiPiiRule();

    @Test
    void detectsCompactAndHyphenatedIdentifiersAtOriginalUtf16Positions() {
        for (String mbi : List.of("1EG4-TE5-MK73", "1EG4TE5MK73", "1eg4-te5-mk73", "2C09-D19-XY00", "9AA0AA0AA00")) {
            // The emoji takes two UTF-16 units, so the match starts at 5.
            String text = "Hi😀 " + mbi + " today";

            assertThat(analyze(text)).as(mbi)
                .containsExactly(new PiiSpan("US_MBI", 5, 5 + mbi.length(), 0.85));
        }
    }

    @Test
    void rejectsCharactersNotAssignedToEachPosition() {
        for (String text : List.of(
            "0EG4-TE5-MK73", // Position 1 is 1 through 9.
            "1SG4-TE5-MK73", // S is never used.
            "1EG4-LE5-MK73", // L is never used.
            "1EG4-TE5-OK73", // O is never used.
            "1eg4-te5-ok73", // Nor is a lowercase o.
            "1EG4-TE5-MI73", // I is never used.
            "1EG4-ZE5-MK73", // Z is never used.
            "1EB4-TE5-MK73", // B is never used, even where a digit is also allowed.
            "11G4-TE5-MK73", // Position 2 is a letter.
            "1EGA-TE5-MK73", // Position 4 is a digit.
            "1EG4-TE5-M173")) { // Position 9 is a letter.
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void rejectsOtherForms() {
        for (String text : List.of("1EG4-TE5MK73", "1EG4TE5-MK73", "1EG4 TE5 MK73",
            "x1EG4TE5MK73", "1EG4TE5MK73x", "11EG4TE5MK73", "1EG4TE5MK734")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    private List<PiiSpan> analyze(String text) {
        return this.rule.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
