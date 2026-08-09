package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

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
    void approvedCandidateProducesTheExistingSpanAndScore() {
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule(
                        "EMPLOYEE_ID",
                        "\\bEMP-\\d{4}\\b",
                        0.9,
                        0,
                        validator("employee-id", candidate -> true)
                )
        ));

        assertThat(analyzer.analyze("Owner EMP-1234 approved it.", PiiAnalysisOptions.defaults()))
                .containsExactly(new PiiSpan("EMPLOYEE_ID", 6, 14, 0.9));
    }

    @Test
    void rejectedCandidateIsExcluded() {
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule(
                        "EMPLOYEE_ID",
                        "\\bEMP-\\d{4}\\b",
                        0.9,
                        0,
                        validator("employee-id", candidate -> candidate.endsWith("0"))
                )
        ));

        assertThat(analyzer.analyze(
                "EMP-1234 and EMP-5670",
                PiiAnalysisOptions.defaults()
        )).containsExactly(new PiiSpan("EMPLOYEE_ID", 13, 21, 0.9));
    }

    @Test
    void validatorReceivesOnlyTheCaptureGroupCandidate() {
        AtomicReference<String> receivedCandidate = new AtomicReference<>();
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule(
                        "TICKET_ID",
                        "ticket[:=]\\s*(TKT-\\d{3})",
                        0.8,
                        1,
                        validator("ticket-id", candidate -> {
                            receivedCandidate.set(candidate);
                            return true;
                        })
                )
        ));

        assertThat(analyzer.analyze(
                "internal ticket: TKT-123 is linked",
                PiiAnalysisOptions.defaults()
        )).containsExactly(new PiiSpan("TICKET_ID", 17, 24, 0.8));
        assertThat(receivedCandidate).hasValue("TKT-123");
    }

    @Test
    void validatorFailureUsesTheExistingAnalyzerFailurePolicyWithoutLeakingTheCandidate() {
        String candidate = "EMP-1234";
        RegexPiiAnalyzer analyzer = new RegexPiiAnalyzer(List.of(
                new RegexPiiRule(
                        "EMPLOYEE_ID",
                        "\\bEMP-\\d{4}\\b",
                        0.9,
                        0,
                        validator("employee-id", value -> {
                            throw new IllegalStateException("invalid " + value);
                        })
                )
        ));

        assertThatThrownBy(() -> analyzer.analyze(candidate, PiiAnalysisOptions.defaults()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Regex match validator failed for entity type EMPLOYEE_ID")
                .hasMessageNotContaining(candidate);

        AtomicReference<PiiAnalyzerFailure> observedFailure = new AtomicReference<>();
        PrivacyService service = new PrivacyService(
                List.of(analyzer, new PiiAnalyzer() {
                    @Override
                    public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                        return List.of();
                    }

                    @Override
                    public String providerId() {
                        return "WORKING";
                    }
                }),
                PiiAnalysisOptions.defaults(),
                EntityTypeRegistry.defaults(),
                PiiResolutionPolicy.builder()
                        .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                        .build(),
                observedFailure::set
        );

        assertThat(service.analyze(candidate)).isEmpty();
        assertThat(observedFailure.get())
                .isEqualTo(new PiiAnalyzerFailure(
                        RegexPiiAnalyzer.PROVIDER_ID,
                        PrivacyFailureCode.ANALYZER_EXECUTION_FAILED,
                        PrivacyPhase.ANALYSIS,
                        1
                ));
        assertThat(observedFailure.get().toString()).doesNotContain(candidate);
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

    private RegexPiiMatchValidator validator(
            String id,
            Predicate<String> validation
    ) {
        return new RegexPiiMatchValidator() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public boolean isValid(String candidate) {
                return validation.test(candidate);
            }
        };
    }
}
