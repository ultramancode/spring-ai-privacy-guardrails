package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KoreanMobilePhonePiiRuleTest {
    private final KoreanMobilePhonePiiRule rule = new KoreanMobilePhonePiiRule();

    @Test
    void detectsSupportedFormsAtOriginalUtf16Positions() {
        for (String number : List.of("01012345678", "010-1234-5678", "010 1234 5678",
                "+821012345678", "+8210-1234-5678", "+82 10-1234-5678",
                "+82 10 1234 5678", "+82-10 1234 5678")) {
            String text = "한글😀 " + number + " 연락";

            assertThat(analyze(text)).as(number)
                    .containsExactly(new PiiSpan("PHONE_NUMBER", 5, 5 + number.length(), 0.85));
        }
    }

    @Test
    void rejectsMalformedCandidates() {
        for (String text : List.of("010-123-5678", "010123456789", "x01012345678", "+8201012345678",
                "010-1234 5678", "010 1234-5678", "0101234-5678", "010-12345678",
                "010.1234.5678", "01012345678x", "01012345678_")) {
            assertThat(analyze(text)).as(text).isEmpty();
        }
    }

    @Test
    void enforcesTheResultSpanLimit() {
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(1).build();

        assertThatThrownBy(() -> this.rule.analyze("010-1234-5678 010-5678-1234",
                PiiAnalysisOptions.defaults(), limits))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class,
                        failure -> assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED));
    }

    @Test
    void rejectsInterruptedAnalysisWithoutClearingTheInterruptFlag() {
        try {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> analyze("010-1234-5678"))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class,
                            failure -> assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYSIS_INTERRUPTED));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private List<PiiSpan> analyze(String text) {
        return this.rule.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }
}
