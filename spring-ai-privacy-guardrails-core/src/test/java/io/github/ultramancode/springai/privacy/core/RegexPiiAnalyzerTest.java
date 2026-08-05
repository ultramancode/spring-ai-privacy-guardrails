package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegexPiiAnalyzerTest {

    @Test
    void constructorRejectsMissingRules() {
        assertThatThrownBy(() -> new RegexPiiAnalyzer(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("rules must not be null");
        assertThatThrownBy(() -> new RegexPiiAnalyzer(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("at least one regex PII rule is required");
    }

    @Test
    void analyzeDetectsRegexMatches() {
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule("EMPLOYEE_ID", "\\bEMP-\\d{4}\\b", 0.9, 0)
        ));

        List<PiiSpan> spans = analyzer.analyze("Owner EMP-1234 approved it.", PiiAnalysisOptions.defaults());

        assertThat(spans).containsExactly(
                new PiiSpan("EMPLOYEE_ID", 6, 14, 0.9)
        );
    }

    @Test
    void privacyServiceTrustsTypesFromItsConfiguredRegexAnalyzer() {
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule("EMPLOYEE_ID", "\\bEMP-\\d{4}\\b", 0.9, 0)
        ));
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        assertThat(service.analyze("Owner EMP-1234 approved it."))
                .singleElement()
                .extracting(ResolvedPiiSpan::entityType)
                .isEqualTo("EMPLOYEE_ID");
    }

    @Test
    void analyzeSupportsCaptureGroupSpans() {
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule("TICKET_ID", "ticket[:=]\\s*(TKT-\\d{3})", 0.8, 1)
        ));

        List<PiiSpan> spans = analyzer.analyze("internal ticket: TKT-123 is linked", PiiAnalysisOptions.defaults());

        assertThat(spans).containsExactly(
                new PiiSpan("TICKET_ID", 17, 24, 0.8)
        );
    }

    @Test
    void constructorRejectsMissingCaptureGroup() {
        assertThatThrownBy(() -> new RegexPiiAnalyzer(List.of(
                new RegexPiiRule("TICKET_ID", "TKT-\\d{3}", 0.8, 1)
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Regex rule capture group 1");
    }

    @Test
    void analyzeFailsWhenConfiguredCaptureGroupDoesNotParticipate() {
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule("TICKET_ID", "(?:ticket:(TKT-\\d{3})|public)", 0.8, 1)
        ));

        assertThatThrownBy(() -> analyzer.analyze("public", PiiAnalysisOptions.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capture group 1")
                .hasMessageContaining("TICKET_ID")
                .hasMessageNotContaining("public");
    }
}
