package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanRrnPiiRuleTest {
    private final KoreanRrnPiiRule rule = new KoreanRrnPiiRule();

    @Test
    void detectsSupportedFormsAtOriginalUtf16Positions() {
        for (String number : List.of("000229-3123456", "9001011234567")) {
            String text = "한글😀 " + number + " 연락";

            assertThat(analyze(text)).as(number)
                .containsExactly(new PiiSpan("KOREAN_RRN", 5, 5 + number.length(), 0.85));
        }
    }

    @Test
    void rejectsInvalidBirthDates() {
        for (String text : List.of("000230-3123456", "990229-1123456")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    private List<PiiSpan> analyze(String text) {
        return this.rule.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
