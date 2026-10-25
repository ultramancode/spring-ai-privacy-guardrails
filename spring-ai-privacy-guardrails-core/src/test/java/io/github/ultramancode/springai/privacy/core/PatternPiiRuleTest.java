package io.github.ultramancode.springai.privacy.core;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class PatternPiiRuleTest {

    @Test
    void buildersRejectMissingRequiredConfiguration() {
        PatternDefinition pattern = PatternDefinition.builder().expression("EMP-[0-9]{4}").score(0.9).build();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PatternDefinition.builder().expression("EMP-[0-9]{4}").build())
                .withMessage("score is required");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PatternDefinition.builder().score(0.9).build())
                .withMessage("expression must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PatternPiiRule.builder()
                        .entityType("EMPLOYEE_ID")
                        .addPattern(pattern)
                        .build())
                .withMessageContaining("rule ID");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PatternPiiRule.builder()
                        .id("employee-id")
                        .addPattern(pattern)
                        .build())
                .withMessage("entityType must not be blank");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PatternPiiRule.builder()
                        .id("employee-id")
                        .entityType("EMPLOYEE_ID")
                        .build())
                .withMessage("at least one pattern is required");
    }

    @Test
    void rejectsCaptureGroupBeyondGroupCount() {
        PatternDefinition ticketIdPattern = PatternDefinition.builder()
                .expression("TKT-\\d{3}")
                .score(0.8)
                .captureGroup(1)
                .build();
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PatternPiiRule.builder()
                        .id("ticket-id")
                        .entityType("TICKET_ID")
                        .addPattern(ticketIdPattern)
                        .build())
                .withMessageContaining("captureGroup exceeds");
    }

    @Test
    void addsPatternsInOrderAndCopiesCollections() {
        PatternDefinition firstPattern = PatternDefinition.builder().expression("EMP-A").score(0.9).build();
        PatternDefinition secondPattern = PatternDefinition.builder().expression("EMP-B").score(0.8).build();
        PatternDefinition thirdPattern = PatternDefinition.builder().expression("EMP-C").score(0.7).build();
        List<PatternDefinition> patterns = new ArrayList<>(List.of(secondPattern, thirdPattern));
        PatternPiiRule.Builder builder = PatternPiiRule.builder()
                .id("employee-id")
                .entityType("EMPLOYEE_ID")
                .addPattern(firstPattern)
                .addPatterns(patterns);

        patterns.clear();
        PatternPiiRule rule = builder.build();

        assertThat(rule.analyze("EMP-C EMP-B EMP-A", PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults()))
                .containsExactly(new PiiSpan("EMPLOYEE_ID", 12, 17, 0.9),
                        new PiiSpan("EMPLOYEE_ID", 6, 11, 0.8),
                        new PiiSpan("EMPLOYEE_ID", 0, 5, 0.7));
    }

    @Test
    void rejectsNullPatternCollectionsAndElements() {
        PatternDefinition employeeIdPattern = PatternDefinition.builder()
                .expression("EMP-[0-9]{4}").score(0.9).build();
        PatternDefinition employeeIdWithoutHyphenPattern = PatternDefinition.builder()
                .expression("EMP[0-9]{4}").score(0.8).build();
        PatternPiiRule.Builder builder = PatternPiiRule.builder()
                .id("employee-id")
                .entityType("EMPLOYEE_ID")
                .addPattern(employeeIdPattern);

        assertThatNullPointerException().isThrownBy(() -> builder.addPatterns(null));
        assertThatNullPointerException().isThrownBy(() ->
                builder.addPatterns(Arrays.asList(employeeIdWithoutHyphenPattern, null)));

        assertThat(builder.build().analyze("EMP-1234 EMP5678", PiiAnalysisOptions.defaults(),
                PrivacyProcessingLimits.defaults()))
                .containsExactly(new PiiSpan("EMPLOYEE_ID", 0, 8, 0.9));
    }

    @Test
    void reusingBuildersDoesNotChangeBuiltRules() {
        PatternDefinition.Builder patternBuilder = PatternDefinition.builder()
                .expression("EMP-[0-9]{4}")
                .score(0.8);
        PatternPiiRule.Builder ruleBuilder = PatternPiiRule.builder()
                .id("employee-id-hyphenated")
                .entityType("EMPLOYEE_ID")
                .addPattern(patternBuilder.build());
        PatternPiiRule first = ruleBuilder.build();

        PatternDefinition employeeIdWithoutHyphenPattern = patternBuilder.expression("EMP[0-9]{4}").score(0.6).build();
        PatternPiiRule second = ruleBuilder.id("employee-id")
                .addPattern(employeeIdWithoutHyphenPattern)
                .build();

        String text = "EMP-1234 EMP5678";
        assertThat(first.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults()))
                .containsExactly(new PiiSpan("EMPLOYEE_ID", 0, 8, 0.8));
        assertThat(second.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults()))
                .containsExactly(new PiiSpan("EMPLOYEE_ID", 0, 8, 0.8),
                        new PiiSpan("EMPLOYEE_ID", 9, 16, 0.6));
        assertThat(first.id()).isEqualTo("employee-id-hyphenated");
        assertThat(second.id()).isEqualTo("employee-id");
    }
}
