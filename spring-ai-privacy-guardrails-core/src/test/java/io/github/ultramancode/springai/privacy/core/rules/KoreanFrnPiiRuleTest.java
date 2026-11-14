package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanFrnPiiRuleTest {
    private final KoreanFrnPiiRule rule = new KoreanFrnPiiRule();

    @Test
    void detectsSupportedFormsAtOriginalUtf16Positions() {
        for (String number : List.of("900101-5234567", "9001016234567", "000229-7123456", "000229-8123456")) {
            String text = "한글😀 " + number + " 연락";

            assertThat(analyze(text)).as(number)
                .containsExactly(new PiiSpan("KR_FRN", 5, 5 + number.length(), 0.85));
        }
    }

    @Test
    void rejectsInvalidBirthDates() {
        // 000229 is February 29. 2000 is a leap year but 1900 is not,
        // so it is invalid with a 1900s digit (5 or 6).
        for (String text : List.of("000230-7123456", "990229-5123456", "000229-5123456", "000229-6123456",
            "901301-5234567", "900100-5234567")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void rejectsOtherSeventhDigitsAndMalformedCandidates() {
        for (String text : List.of("900101-1234567", "900101-4234567", "900101-9234567", "900101-0234567",
            "900101 5234567", "900101-523456", "900101-52345678", "1900101-5234567",
            "x900101-5234567", "900101-5234567x")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    private List<PiiSpan> analyze(String text) {
        return this.rule.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
