package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailPiiRuleTest {
    private final EmailPiiRule rule = new EmailPiiRule();

    @ParameterizedTest
    @ValueSource(strings = {
        "first.last+tag@example.co.kr", "o'connor@example.com", "a!b@example.com",
        "!alerts@example.com", "alerts!@example.com",
        "all!#$%&'*+-/=?^_`{|}~.tags@example.com"
    })
    void detectsAddressAtOriginalUtf16Positions(String address) {
        String text = "한글😀 " + address + " 연락";

        assertThat(analyze(text))
            .containsExactly(new PiiSpan("EMAIL_ADDRESS", 5, 5 + address.length(), 0.85));
    }

    @Test
    void rejectsMalformedCandidates() {
        for (String text : List.of("first..last@example.com", ".first@example.com",
            "first!..last@example.com", "first!.@example.com",
            "a".repeat(65) + "@example.com")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void enforcesLocalPartLengthLimitWithLeadingSpecialCharacter() {
        String maximumLengthAddress = "!" + "a".repeat(63) + "@example.com";
        assertThat(analyze(maximumLengthAddress))
            .containsExactly(new PiiSpan("EMAIL_ADDRESS", 0, maximumLengthAddress.length(), 0.85));

        String tooLongAddress = "!" + "a".repeat(64) + "@example.com";
        assertThat(analyze(tooLongAddress)).isEmpty();
    }

    @Test
    void ignoresLongInputWithoutMatches() {
        assertThat(analyze("a.".repeat(20_000))).isEmpty();
    }

    private List<PiiSpan> analyze(String text) {
        return this.rule.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
