package io.github.ultramancode.springai.privacy.core;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import org.junit.jupiter.api.Test;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

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
        PatternDefinition employeeIdPattern = PatternDefinition.builder()
                .expression("\\bEMP-\\d{4}\\b")
                .score(0.9)
                .build();
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                PatternPiiRule.builder()
                        .id("employee-id")
                        .entityType("EMPLOYEE_ID")
                        .addPattern(employeeIdPattern)
                        .build()
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
        PatternDefinition characterPattern = PatternDefinition.builder()
                .expression(".")
                .score(1.0)
                .build();
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                PatternPiiRule.builder()
                        .id("character")
                        .entityType("CHARACTER")
                        .addPattern(characterPattern)
                        .build()
        ));
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(1).build();

        assertThatThrownBy(() -> analyzer.analyzeSegments(
                List.of("a", "b"), PiiAnalysisOptions.defaults(), limits
        )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure ->
                assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED));
    }

    @Test
    void privacyServiceTrustsTypesFromItsConfiguredRulesAnalyzer() {
        PatternDefinition employeeIdPattern = PatternDefinition.builder()
                .expression("\\bEMP-\\d{4}\\b")
                .score(0.9)
                .build();
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                PatternPiiRule.builder()
                        .id("employee-id")
                        .entityType("EMPLOYEE_ID")
                        .addPattern(employeeIdPattern)
                        .build()
        ));
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        assertThat(service.analyze("Owner EMP-1234 approved it."))
                .singleElement()
                .extracting(ResolvedPiiSpan::entityType)
                .isEqualTo("EMPLOYEE_ID");
    }

    @Test
    void rejectedCandidateIsExcluded() {
        PatternDefinition employeeIdPattern = PatternDefinition.builder()
                .expression("\\bEMP-\\d{4}\\b")
                .score(0.9)
                .validator(candidate -> candidate.endsWith("0"))
                .build();
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                PatternPiiRule.builder()
                        .id("employee-id")
                        .entityType("EMPLOYEE_ID")
                        .addPattern(employeeIdPattern)
                        .build()
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
        PatternDefinition ticketIdPattern = PatternDefinition.builder()
                .expression("ticket[:=]\\s*(TKT-\\d{3})")
                .score(0.8)
                .captureGroup(1)
                .validator(candidate -> {
                    receivedCandidate.set(candidate);
                    return true;
                })
                .build();
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                PatternPiiRule.builder()
                        .id("ticket-id")
                        .entityType("TICKET_ID")
                        .addPattern(ticketIdPattern)
                        .build()
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
        PatternDefinition employeeIdPattern = PatternDefinition.builder()
                .expression("\\bEMP-\\d{4}\\b")
                .score(0.9)
                .validator(value -> {
                    throw new IllegalStateException("invalid " + value);
                })
                .build();
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                PatternPiiRule.builder()
                        .id("employee-id")
                        .entityType("EMPLOYEE_ID")
                        .addPattern(employeeIdPattern)
                        .build()
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
    void analyzeFailsWhenConfiguredCaptureGroupDoesNotParticipate() {
        PatternDefinition ticketIdPattern = PatternDefinition.builder()
                .expression("(?:ticket:(TKT-\\d{3})|public)")
                .score(0.8)
                .captureGroup(1)
                .build();
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(
                PatternPiiRule.builder()
                        .id("ticket-id")
                        .entityType("TICKET_ID")
                        .addPattern(ticketIdPattern)
                        .build()
        ));

        assertThatThrownBy(() -> analyzer.analyze(
                "public", PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ticket-id")
                .hasMessageNotContaining("public");
    }

    @Test
    void enforcesTheSpanLimitAcrossRules() {
        PiiRule first = rule("first", text -> List.of(new PiiSpan("CUSTOM_ID", 0, 1, 1)));
        PiiRule second = rule("second", text -> List.of(new PiiSpan("CUSTOM_ID", 1, 2, 1)));
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(first, second));
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(1).build();

        assertThatThrownBy(() -> analyzer.analyze("ab", PiiAnalysisOptions.defaults(), limits))
                .isInstanceOfSatisfying(PrivacyGuardrailException.class,
                        failure -> assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED));
    }

    @Test
    void allowsRulesWithoutMatchesAfterTheSpanLimitIsReached() {
        PiiRule first = rule("first", text -> List.of(new PiiSpan("CUSTOM_ID", 0, 1, 1)));
        PiiRule empty = rule("empty", text -> List.of());
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(first, empty));
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(1).build();

        assertThat(analyzer.analyze("ab", PiiAnalysisOptions.defaults(), limits))
                .containsExactly(new PiiSpan("CUSTOM_ID", 0, 1, 1));
    }

    @Test
    void ruleFailuresDoNotReturnPartialResultsOrExposeInput() {
        PiiRule first = rule("first", text -> List.of(new PiiSpan("CUSTOM_ID", 0, 1, 1)));
        for (PiiRule failingRule : List.of(
                rule("outside", text -> List.of(new PiiSpan("CUSTOM_ID", 0, 100, 1))),
                rule("undeclared", text -> List.of(new PiiSpan("OTHER_ID", 0, 1, 1))),
                rule("null-result", text -> null),
                rule("failure", text -> {
                    throw new IllegalStateException(text);
                }))) {
            RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(first, failingRule));

            assertThatThrownBy(() -> analyze(analyzer, "private-value"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(failingRule.id())
                    .hasMessageNotContaining("private-value")
                    .hasNoCause();
        }
    }

    @Test
    void interruptionRemainsFatalAndDoesNotInvokeRules() {
        AtomicInteger calls = new AtomicInteger();
        PiiRule rule = rule("custom", text -> {
            calls.incrementAndGet();
            return List.of();
        });
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
        PatternDefinition calendarDatePattern = PatternDefinition.builder()
                .expression("date:([0-9]{4}-[0-9]{2}-[0-9]{2})")
                .score(0.8)
                .captureGroup(1)
                .validator(candidate -> {
                    try {
                        LocalDate.parse(candidate);
                        return true;
                    } catch (DateTimeException invalidDate) {
                        return false;
                    }
                })
                .build();
        PatternDefinition relativeDatePattern = PatternDefinition.builder()
                .expression("today")
                .score(0.5)
                .build();
        PatternPiiRule rule = PatternPiiRule.builder()
                .id("date")
                .entityType(PiiEntityTypes.DATE_TIME)
                .addPattern(calendarDatePattern)
                .addPattern(relativeDatePattern)
                .build();
        assertThat(analyze(new RuleBasedPiiAnalyzer(List.of(rule)),
                "😀 date:2000-02-29 date:2000-02-30 today"))
                .containsExactly(new PiiSpan("DATE_TIME", 8, 18, 0.8),
                        new PiiSpan("DATE_TIME", 35, 40, 0.5));
    }

    private static List<PiiSpan> analyze(PiiAnalyzer analyzer, String text) {
        return analyzer.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults());
    }

    private static PiiRule rule(String ruleId, Function<String, List<PiiSpan>> detector) {
        return new PiiRule() {
            @Override
            public String id() {
                return ruleId;
            }

            @Override
            public Set<String> entityTypes() {
                return Set.of("CUSTOM_ID");
            }

            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
                return detector.apply(text);
            }
        };
    }
}
