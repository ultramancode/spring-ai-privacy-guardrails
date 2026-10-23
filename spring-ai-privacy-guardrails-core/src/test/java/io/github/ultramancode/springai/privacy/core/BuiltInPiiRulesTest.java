package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class BuiltInPiiRulesTest {
    private final PiiRuleCatalog catalog = PiiRuleCatalog.builder().builtIns().build();

    @Test
    void detectsSupportedFormsAtOriginalUtf16Positions() {
        for (String[] example : List.of(
                new String[]{"email", "first.last+tag@example.co.kr"},
                new String[]{"credit-card", "4111 1111 1111 1111"},
                new String[]{"credit-card", "4111-1111-1111-1111"},
                new String[]{"kr-mobile-phone", "01012345678"},
                new String[]{"kr-mobile-phone", "010-1234-5678"},
                new String[]{"kr-mobile-phone", "010 1234 5678"},
                new String[]{"kr-mobile-phone", "+821012345678"},
                new String[]{"kr-mobile-phone", "+82 10-1234-5678"},
                new String[]{"kr-rrn", "000229-3123456"},
                new String[]{"kr-rrn", "9001011234567"})) {
            String text = "한글😀 " + example[1] + " 연락";
            List<PiiSpan> spans = analyze(example[0], text);
            assertThat(spans).as(example[0] + ": " + example[1]).hasSize(1);
            assertThat(text.substring(spans.get(0).start(), spans.get(0).end())).isEqualTo(example[1]);
            assertThat(spans.get(0).start()).isEqualTo(5);
        }
    }

    @Test
    void rejectsMalformedCandidates() {
        for (String[] example : List.of(
                new String[]{"email", "first..last@example.com"},
                new String[]{"email", ".first@example.com"},
                new String[]{"email", "a".repeat(65) + "@example.com"},
                new String[]{"credit-card", "4111 1111 1111 1112"},
                new String[]{"credit-card", "0000000000000000"},
                new String[]{"credit-card", "41111111111111111111"},
                new String[]{"credit-card", "99999-4111-1111-1111-1111"},
                new String[]{"kr-mobile-phone", "010-123-5678"},
                new String[]{"kr-mobile-phone", "010123456789"},
                new String[]{"kr-mobile-phone", "x01012345678"},
                new String[]{"kr-mobile-phone", "+8201012345678"},
                new String[]{"kr-rrn", "000230-3123456"},
                new String[]{"kr-rrn", "990229-1123456"})) {
            assertThat(analyze(example[0], example[1])).as(example[0] + ": " + example[1]).isEmpty();
        }
    }

    @Test
    void longNonMatchingInputAndCountrySelectionDoNotDependOnLanguage() {
        assertThat(analyze("email", "a.".repeat(20_000))).isEmpty();
        PiiAnalyzer kr = this.catalog.select(List.of("KR"), List.of(), List.of());
        assertThat(kr.analyze("010-1234-5678", PiiAnalysisOptions.builder().language("en").build(),
                PrivacyProcessingLimits.defaults())).hasSize(1);
    }

    private List<PiiSpan> analyze(String id, String text) {
        return this.catalog.select(List.of(), List.of(id), List.of())
                .analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
