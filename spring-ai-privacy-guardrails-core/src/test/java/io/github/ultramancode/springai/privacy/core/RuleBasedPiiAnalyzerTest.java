package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleBasedPiiAnalyzerTest {

    @Test
    void constructorRejectsMissingRules() {
        assertThatThrownBy(() -> new RuleBasedPiiAnalyzer(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("rules must not be null");
        assertThatThrownBy(() -> new RuleBasedPiiAnalyzer(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("at least one PII rule must be selected");
    }

    @Test
    void analyzeDetectsRegexMatches() {
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                new PatternPiiRule("employee-id", "EMPLOYEE_ID", "\\bEMP-\\d{4}\\b", 0.9, 0)
        ));

        List<PiiSpan> spans = analyzer.analyze(
                "Owner EMP-1234 approved it.", PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults()
        );

        assertThat(spans).containsExactly(
                new PiiSpan("EMPLOYEE_ID", 6, 14, 0.9)
        );
    }

    @Test
    void batchAnalysisAppliesTheSpanLimitAcrossTexts() {
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                new PatternPiiRule("character", "CHARACTER", ".", 1.0, 0)
        ));
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(1).build();

        assertThatThrownBy(() -> analyzer.analyzeSegments(
                List.of("a", "b"), PiiAnalysisOptions.defaults(), limits
        )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure ->
                assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED));
    }

    @Test
    void privacyServiceTrustsTypesFromItsConfiguredRulesAnalyzer() {
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                new PatternPiiRule("employee-id", "EMPLOYEE_ID", "\\bEMP-\\d{4}\\b", 0.9, 0)
        ));
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        assertThat(service.analyze("Owner EMP-1234 approved it."))
                .singleElement()
                .extracting(ResolvedPiiSpan::entityType)
                .isEqualTo("EMPLOYEE_ID");
    }

    @Test
    void rejectedCandidateIsExcluded() {
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                new PatternPiiRule("employee-id", "EMPLOYEE_ID",
                        "\\bEMP-\\d{4}\\b",
                        0.9,
                        0,
                        candidate -> candidate.endsWith("0")
                )
        ));

        assertThat(analyzer.analyze(
                "EMP-1234 and EMP-5670",
                PiiAnalysisOptions.defaults(),
                PrivacyProcessingLimits.defaults()
        )).containsExactly(new PiiSpan("EMPLOYEE_ID", 13, 21, 0.9));
    }

    @Test
    void validatorReceivesOnlyTheCaptureGroupCandidate() {
        AtomicReference<String> receivedCandidate = new AtomicReference<>();
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                new PatternPiiRule("ticket-id", "TICKET_ID",
                        "ticket[:=]\\s*(TKT-\\d{3})",
                        0.8,
                        1,
                        candidate -> {
                            receivedCandidate.set(candidate);
                            return true;
                        }
                )
        ));

        assertThat(analyzer.analyze(
                "internal ticket: TKT-123 is linked",
                PiiAnalysisOptions.defaults(),
                PrivacyProcessingLimits.defaults()
        )).containsExactly(new PiiSpan("TICKET_ID", 17, 24, 0.8));
        assertThat(receivedCandidate).hasValue("TKT-123");
    }

    @Test
    void validatorFailureFollowsProviderPolicyAndOmitsCandidate() {
        String candidate = "EMP-1234";
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                new PatternPiiRule("employee-id", "EMPLOYEE_ID",
                        "\\bEMP-\\d{4}\\b",
                        0.9,
                        0,
                        value -> {
                            throw new IllegalStateException("invalid " + value);
                        }
                )
        ));

        assertThatThrownBy(() -> analyzer.analyze(
                candidate, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PII rule 'employee-id' failed")
                .hasMessageNotContaining(candidate);

        AtomicReference<PiiAnalyzerFailure> observedFailure = new AtomicReference<>();
        PrivacyService service = new PrivacyService(
                List.of(analyzer, new PiiAnalyzer() {
                    @Override
                    public List<PiiSpan> analyze(
                            String text,
                            PiiAnalysisOptions options,
                            PrivacyProcessingLimits limits
                    ) {
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
                observedFailure::set,
                PrivacyProcessingLimits.defaults()
        );

        assertThat(service.analyze(candidate)).isEmpty();
        assertThat(observedFailure.get())
                .isEqualTo(new PiiAnalyzerFailure(
                        RuleBasedPiiAnalyzer.PROVIDER_ID,
                        PrivacyFailureCode.ANALYZER_EXECUTION_FAILED,
                        PrivacyPhase.ANALYSIS,
                        1
                ));
        assertThat(observedFailure.get().toString()).doesNotContain(candidate);
    }

    @Test
    void constructorRejectsMissingCaptureGroup() {
        assertThatThrownBy(() -> new RuleBasedPiiAnalyzer(List.of(
                new PatternPiiRule("ticket-id", "TICKET_ID", "TKT-\\d{3}", 0.8, 1)
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("captureGroup exceeds");
    }

    @Test
    void analyzeFailsWhenConfiguredCaptureGroupDoesNotParticipate() {
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                new PatternPiiRule("ticket-id", "TICKET_ID", "(?:ticket:(TKT-\\d{3})|public)", 0.8, 1)
        ));

        assertThatThrownBy(() -> analyzer.analyze(
                "public", PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ticket-id")
                .hasMessageNotContaining("public");
    }

    @Test
    void enforcesCombinedBudgetAndValidatesCustomResultsWithoutReturningPartialSuccess() {
        PiiRule first = rule("first", text -> List.of(new PiiSpan("CUSTOM_ID", 0, 1, 1)));
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(first,
                rule("second", text -> List.of(new PiiSpan("CUSTOM_ID", 1, 2, 1)))));
        assertThatThrownBy(() -> analyzer.analyze("ab", PiiAnalysisOptions.defaults(),
                PrivacyProcessingLimits.builder().maxResultSpans(1).build()))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class,
                        e -> assertThat(e.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED));
        assertThat(new RuleBasedPiiAnalyzer(List.of(first, rule("empty", text -> List.of())))
                .analyze("ab", PiiAnalysisOptions.defaults(),
                        PrivacyProcessingLimits.builder().maxResultSpans(1).build())).hasSize(1);
        for (PiiRule invalid : List.of(
                rule("outside", text -> List.of(new PiiSpan("CUSTOM_ID", 0, 100, 1))),
                rule("undeclared", text -> List.of(new PiiSpan("OTHER_ID", 0, 1, 1))),
                rule("null-result", text -> null),
                rule("failure", text -> { throw new IllegalStateException(text); }))) {
            assertThatThrownBy(() -> analyze(new RuleBasedPiiAnalyzer(List.of(first, invalid)), "private-value"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(invalid.id())
                    .hasMessageNotContaining("private-value").hasNoCause();
        }
    }

    @Test
    void interruptionRemainsFatalAndDoesNotInvokeRules() {
        AtomicInteger calls = new AtomicInteger();
        PiiRule rule = rule("custom", text -> { calls.incrementAndGet(); return List.of(); });
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> analyze(new RuleBasedPiiAnalyzer(List.of(rule)), "text"))
                    .isInstanceOfSatisfying(PrivacyGuardrailException.class,
                            e -> assertThat(e.code()).isEqualTo(PrivacyFailureCode.ANALYSIS_INTERRUPTED));
            assertThat(calls).hasValue(0);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void multiplePatternsAndParsingPredicateKeepOriginalCaptureOffsets() {
        PatternPiiRule rule = new PatternPiiRule("date", "DATE_TIME", List.of(
                new PatternPiiRule.Pattern("date:([0-9]{4}-[0-9]{2}-[0-9]{2})", 0.8, 1, candidate -> {
                    try { java.time.LocalDate.parse(candidate); return true; }
                    catch (java.time.DateTimeException invalid) { return false; }
                }),
                new PatternPiiRule.Pattern("today", 0.5, 0, null)), Set.of());
        assertThat(analyze(new RuleBasedPiiAnalyzer(List.of(rule)),
                "😀 date:2000-02-29 date:2000-02-30 today"))
                .containsExactly(new PiiSpan("DATE_TIME", 8, 18, 0.8),
                        new PiiSpan("DATE_TIME", 35, 40, 0.5));
    }

    private static List<PiiSpan> analyze(PiiAnalyzer analyzer, String text) {
        return analyzer.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }

    private static PiiRule rule(String id, Function<String, List<PiiSpan>> detector) {
        return new PiiRule() {
            public String id() { return id; }
            public Set<String> entityTypes() { return Set.of("CUSTOM_ID"); }
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
                return detector.apply(text);
            }
        };
    }
}
