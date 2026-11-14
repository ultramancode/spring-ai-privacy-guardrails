package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanDriverLicensePiiRuleTest {
    private final KoreanDriverLicensePiiRule rule = new KoreanDriverLicensePiiRule();

    @Test
    void detectsHyphenatedNumbersAtOriginalUtf16Positions() {
        for (String number : List.of("11-95-123456-78", "19-03-654321-09", "20-10-123456-00",
            "26-21-000001-12", "28-99-999999-99")) {
            String text = "한글😀 " + number + " 연락";

            assertThat(analyze(text)).as(number)
                .containsExactly(new PiiSpan("KR_DRIVER_LICENSE", 5, 5 + number.length(), 0.85));
        }
    }

    @Test
    void detectsNumbersNextToKoreanTextAndPunctuation() {
        for (String text : List.of("운전면허번호11-95-123456-78입니다", "(11-95-123456-78)",
            "번호-11-95-123456-78-끝")) {
            assertThat(analyze(text)).as(text).hasSize(1);
        }
    }

    @Test
    void rejectsUnknownRegionCodes() {
        for (String text : List.of("00-95-123456-78", "10-95-123456-78", "27-95-123456-78",
            "29-95-123456-78", "99-95-123456-78")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void rejectsOtherFormsOfAKnownRegionNumber() {
        for (String text : List.of("119512345678", "11 95 123456 78", "11.95.123456.78", "11-95 123456-78",
            "1195-123456-78", "11-95-12345678", "11-95-12345-78", "11-95-1234567-78", "11-95-123456-7",
            "x11-95-123456-78", "11-95-123456-78x", "011-95-123456-78", "11-95-123456-789")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    private List<PiiSpan> analyze(String text) {
        return this.rule.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
