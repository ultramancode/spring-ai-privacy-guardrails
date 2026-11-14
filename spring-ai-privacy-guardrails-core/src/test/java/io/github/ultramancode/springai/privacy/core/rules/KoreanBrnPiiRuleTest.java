package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KoreanBrnPiiRuleTest {
    private final KoreanBrnPiiRule rule = new KoreanBrnPiiRule();

    @Test
    void detectsHyphenatedNumbersAtOriginalUtf16Positions() {
        // 314-15-92650 has a weighted sum ending in 0, so its check digit is 0.
        for (String number : List.of("123-45-67891", "987-65-43215", "246-80-13573", "314-15-92650")) {
            String text = "한글😀 " + number + " 연락";

            assertThat(analyze(text)).as(number)
                .containsExactly(new PiiSpan("KR_BRN", 5, 5 + number.length(), 0.85));
        }
    }

    @Test
    void detectsNumbersNextToKoreanTextAndPunctuation() {
        for (String text : List.of("사업자등록번호123-45-67891입니다", "(123-45-67891)", "번호-123-45-67891-끝")) {
            assertThat(analyze(text)).as(text).hasSize(1);
        }
    }

    @Test
    void rejectsInvalidCheckDigits() {
        // 123-45-67895 is the value a checksum without the ninth product's tens digit would accept.
        for (String text : List.of("123-45-67890", "123-45-67895", "987-65-43216", "246-80-13570")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void rejectsNumbersFollowedByAHyphenAndADigit() {
        // 979-11-89726 passes the check digit, but here it is the start of an ISBN.
        assertThat(analyze("979-11-89726")).hasSize(1);
        for (String text : List.of("979-11-89726-02-6", "123-45-67891-2")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void rejectsOtherFormsOfAValidNumber() {
        for (String text : List.of("1234567891", "123 45 67891", "123.45.67891", "123-4567891", "12345-67891",
            "123-45 67891", "x123-45-67891", "123-45-67891x", "0123-45-67891", "123-45-678915")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    private List<PiiSpan> analyze(String text) {
        return this.rule.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
