package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PatternPiiRule;
import io.github.ultramancode.springai.privacy.core.PatternPiiRule.PatternDefinition;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import io.github.ultramancode.springai.privacy.core.PiiRuleCatalog;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import io.github.ultramancode.springai.privacy.core.RuleBasedPiiAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BuiltInPiiRulesTest {
    @Test
    void registersBuiltInRules() {
        assertThat(PiiRuleCatalog.builder().builtIns().build().ruleIds())
                .containsExactly("credit-card", "email", "kr-mobile-phone", "kr-rrn");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void definesCommonAndKoreanPresets(boolean overrideScores) {
        PiiRuleCatalog.Builder builder = PiiRuleCatalog.builder();
        if (overrideScores) {
            builder.builtInsWithScores(Map.of(BuiltInPiiRuleIds.EMAIL, 0.95));
        } else {
            builder.builtIns();
        }
        PiiRuleCatalog catalog = builder.build();
        assertThat(catalog.analyzerBuilder().presets("COMMON").build().ruleIds())
                .containsExactly("credit-card", "email");
        assertThat(catalog.analyzerBuilder().presets("KR").build().ruleIds())
                .containsExactly("kr-mobile-phone", "kr-rrn");
    }

    @ParameterizedTest
    @CsvSource({
            "email, EMAIL_ADDRESS, dev@example.com, 0.9",
            "credit-card, CREDIT_CARD, 4111111111111111, 1.0",
            "kr-mobile-phone, PHONE_NUMBER, 010-1234-5678, 0.0",
            "kr-rrn, KOREAN_RRN, 900101-1234567, 0.6"
    })
    void overridesEachBuiltInScoreAndPreservesMetadata(String ruleId, String entityType, String candidate, double score) {
        PiiRule original = BuiltInPiiRules.rules().stream()
                .filter(rule -> rule.id().equals(ruleId)).findFirst().orElseThrow();
        PiiRule configured = BuiltInPiiRules.rulesWithScores(Map.of(ruleId, score)).stream()
                .filter(rule -> rule.id().equals(ruleId)).findFirst().orElseThrow();
        assertThat(configured.entityTypes()).isEqualTo(original.entityTypes());

        String text = "한글😀 " + candidate;
        assertThat(configured.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults()))
                .containsExactly(new PiiSpan(entityType, 5, text.length(), score));
        assertThat(original.analyze(text, PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults()))
                .containsExactly(new PiiSpan(entityType, 5, text.length(), 0.85));
    }

    @Test
    void scoreOverridesDoNotBypassCandidateValidation() {
        RuleBasedPiiAnalyzer analyzer = PiiRuleCatalog.builder()
                .builtInsWithScores(Map.of(
                        BuiltInPiiRuleIds.EMAIL, 1.0,
                        BuiltInPiiRuleIds.CREDIT_CARD, 1.0,
                        BuiltInPiiRuleIds.KR_MOBILE_PHONE, 1.0,
                        BuiltInPiiRuleIds.KR_RRN, 1.0))
                .build().analyzerBuilder().presets(BuiltInPiiPresetNames.COMMON, BuiltInPiiPresetNames.KR).build();

        assertThat(analyzer.analyze("a..b@example.com | 4111111111111112 | 010-123-5678 | 990230-1234567",
                PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults())).isEmpty();
    }

    @Test
    void snapshotsOverridesAndPreservesUnspecifiedAndApplicationRuleScores() {
        Map<String, Double> scoreOverrides = new HashMap<>(Map.of(BuiltInPiiRuleIds.EMAIL, 0.95));
        PatternPiiRule applicationRule = PatternPiiRule.builder()
                .id("company-id")
                .entityType("CUSTOM_ID")
                .addPattern(PatternDefinition.builder().expression("low").score(0.3).build())
                .addPattern(PatternDefinition.builder().expression("high").score(0.8).build())
                .build();
        PiiRuleCatalog.Builder builder = PiiRuleCatalog.builder()
                .builtInsWithScores(scoreOverrides)
                .addRule(applicationRule);
        scoreOverrides.put(BuiltInPiiRuleIds.EMAIL, 0.1);
        RuleBasedPiiAnalyzer analyzer = builder.build()
                .analyzerBuilder().presets(BuiltInPiiPresetNames.COMMON).include("company-id").build();

        assertThat(analyzer.analyze("dev@example.com | 4111111111111111 | low high",
                PiiAnalysisOptions.defaults(), PrivacyProcessingLimits.defaults())).containsExactly(
                        new PiiSpan("CREDIT_CARD", 18, 34, 0.85),
                        new PiiSpan("EMAIL_ADDRESS", 0, 15, 0.95),
                        new PiiSpan("CUSTOM_ID", 37, 40, 0.3),
                        new PiiSpan("CUSTOM_ID", 41, 45, 0.8));
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 1.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
    void rejectsInvalidScoresBeforeRuleSelection(double score) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                PiiRuleCatalog.builder().builtInsWithScores(Map.of(BuiltInPiiRuleIds.EMAIL, score)))
                .withMessageContaining("email").withMessageContaining("finite and between 0.0 and 1.0");
    }

    @Test
    void rejectsUnknownIdsAndNullScores() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                PiiRuleCatalog.builder().builtInsWithScores(Map.of("not-a-built-in", 0.9)))
                .withMessage("Unknown built-in rule ID 'not-a-built-in'");
        Map<String, Double> scoreOverrides = new HashMap<>();
        scoreOverrides.put(BuiltInPiiRuleIds.EMAIL, null);
        assertThatIllegalArgumentException().isThrownBy(() -> BuiltInPiiRules.rulesWithScores(scoreOverrides))
                .withMessageContaining("email").withMessageContaining("finite and between 0.0 and 1.0");
    }
}
