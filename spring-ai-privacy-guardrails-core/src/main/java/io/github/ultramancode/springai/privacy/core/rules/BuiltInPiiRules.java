package io.github.ultramancode.springai.privacy.core.rules;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiRule;
import io.github.ultramancode.springai.privacy.core.PiiRulePreset;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Library-provided PII rules and presets. */
public final class BuiltInPiiRules {
    private BuiltInPiiRules() {
    }

    /** Creates the built-in rules. */
    public static List<PiiRule> rules() {
        return List.of(
                new CreditCardPiiRule(),
                new EmailPiiRule(),
                new KoreanMobilePhonePiiRule(),
                new KoreanRrnPiiRule()
        );
    }

    /** Creates the built-in presets with rule IDs in evaluation order. */
    public static List<PiiRulePreset> presets() {
        return List.of(
                new PiiRulePreset(BuiltInPiiPresetNames.COMMON, List.of(
                        BuiltInPiiRuleIds.CREDIT_CARD,
                        BuiltInPiiRuleIds.EMAIL
                )),
                new PiiRulePreset(BuiltInPiiPresetNames.KR, List.of(
                        BuiltInPiiRuleIds.KR_MOBILE_PHONE,
                        BuiltInPiiRuleIds.KR_RRN
                ))
        );
    }

    /**
     * Creates the built-in rules with fixed scores for the specified rule IDs.
     * Unspecified rules retain their default scores. Specified scores are applied to
     * matches accepted by each rule's detection and validation.
     *
     * @param scoreOverrides replacement scores by built-in rule ID, each finite and between 0.0 and 1.0
     * @return the built-in rules in registration order
     * @throws IllegalArgumentException if a rule ID is unknown or a score is outside its range
     */
    public static List<PiiRule> rulesWithScores(Map<String, Double> scoreOverrides) {
        Objects.requireNonNull(scoreOverrides, "scoreOverrides must not be null");
        Map<String, PiiRule> rulesById = new LinkedHashMap<>();
        for (PiiRule rule : rules()) {
            rulesById.put(rule.id(), rule);
        }
        for (Map.Entry<String, Double> entry : scoreOverrides.entrySet()) {
            String ruleId = entry.getKey();
            PiiRule rule = rulesById.get(ruleId);
            if (rule == null) {
                throw new IllegalArgumentException("Unknown built-in rule ID '" + ruleId + "'");
            }
            Double score = entry.getValue();
            if (score == null || !Double.isFinite(score) || score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("Score for built-in rule '" + ruleId
                        + "' must be finite and between 0.0 and 1.0");
            }
            rulesById.put(ruleId, new ScoreOverridePiiRule(rule, score));
        }
        return List.copyOf(rulesById.values());
    }

    private record ScoreOverridePiiRule(PiiRule delegate, double score) implements PiiRule {
        @Override
        public String id() {
            return this.delegate.id();
        }

        @Override
        public Set<String> entityTypes() {
            return this.delegate.entityTypes();
        }

        @Override
        public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
            return this.delegate.analyze(text, options, limits).stream()
                    .map(span -> new PiiSpan(span.entityType(), span.start(), span.end(), this.score))
                    .toList();
        }
    }
}
