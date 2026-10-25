package io.github.ultramancode.springai.privacy.core;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
    void executesRulesInOrderAndCollectsTheirResults() {
        List<String> executedRuleIds = new ArrayList<>();
        PiiSpan firstRuleSpan = new PiiSpan("CUSTOM_ID", 1, 2, 0.8);
        PiiSpan secondRuleSpan = new PiiSpan("CUSTOM_ID", 0, 1, 0.9);
        PiiRule firstRule = rule("first", text -> {
            assertThat(text).isEqualTo("ab");
            executedRuleIds.add("first");
            return List.of(firstRuleSpan);
        });
        PiiRule secondRule = rule("second", text -> {
            assertThat(text).isEqualTo("ab");
            executedRuleIds.add("second");
            return List.of(secondRuleSpan);
        });
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(firstRule, secondRule));

        List<PiiSpan> spans = analyze(analyzer, "ab");

        assertThat(executedRuleIds).containsExactly("first", "second");
        assertThat(spans).containsExactly(firstRuleSpan, secondRuleSpan);
    }

    @Test
    void segmentedAnalysisEnforcesTheSpanLimitAcrossTexts() {
        PiiRule rule = rule("custom", text -> List.of(new PiiSpan("CUSTOM_ID", 0, 1, 1.0)));
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(rule));
        PrivacyProcessingLimits limits = PrivacyProcessingLimits.builder().maxResultSpans(1).build();

        assertThatThrownBy(() -> analyzer.analyzeSegments(
            List.of("a", "b"), PiiAnalysisOptions.defaults(), limits
        )).isInstanceOfSatisfying(PrivacyGuardrailException.class, failure ->
            assertThat(failure.code()).isEqualTo(PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED));
    }

    @Test
    void privacyServiceTrustsTypesFromItsConfiguredRulesAnalyzer() {
        PiiRule rule = rule("custom", text -> List.of(new PiiSpan("CUSTOM_ID", 0, 1, 0.9)));
        RuleBasedPiiAnalyzer analyzer = new RuleBasedPiiAnalyzer(List.of(rule));
        PrivacyService service = new PrivacyService(List.of(analyzer), PiiAnalysisOptions.defaults());

        assertThat(service.analyze("x"))
            .singleElement()
            .extracting(ResolvedPiiSpan::entityType)
            .isEqualTo("CUSTOM_ID");
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
