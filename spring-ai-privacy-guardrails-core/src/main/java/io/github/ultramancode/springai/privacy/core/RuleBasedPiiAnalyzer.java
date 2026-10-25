package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Executes the configured PII rules in order and collects their results.
 * Returns an empty list when no rules detect PII.
 * Exceptions or invalid rule results abort the analysis call without returning partial results.
 * The result count limit applies to the total across all rules.
 */
public final class RuleBasedPiiAnalyzer implements PiiAnalyzer {
    /** Provider ID for resolution and failure policies. */
    public static final String PROVIDER_ID = "RULES";
    private final List<RegisteredRule> rules;
    private final List<String> presetNames;
    private final Set<String> entityTypes;

    /**
     * Creates an analyzer that executes the supplied rules in list order.
     * The list must be nonempty and rule IDs must be unique.
     */
    public RuleBasedPiiAnalyzer(List<? extends PiiRule> rules) {
        this(rules, List.of());
    }

    RuleBasedPiiAnalyzer(List<? extends PiiRule> rules, List<String> presetNames) {
        Objects.requireNonNull(rules, "rules must not be null");
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("at least one PII rule must be selected");
        }
        Set<String> ruleIds = new HashSet<>();
        Set<String> entityTypes = new LinkedHashSet<>();
        List<RegisteredRule> registeredRules = new ArrayList<>();
        for (PiiRule rule : rules) {
            Objects.requireNonNull(rule, "rule must not be null");
            String ruleId = PiiRuleSupport.requireValidRuleId(rule.id());
            if (!ruleIds.add(ruleId)) {
                throw new IllegalArgumentException("Duplicate rule ID '" + ruleId + "'");
            }
            Set<String> ruleEntityTypes = Set.copyOf(rule.entityTypes());
            if (ruleEntityTypes.isEmpty()) {
                throw new IllegalArgumentException("Rule '" + ruleId + "' must declare entity types");
            }
            ruleEntityTypes.forEach(EntityTypeRegistry::requireValidEntityType);
            registeredRules.add(new RegisteredRule(ruleId, ruleEntityTypes, rule));
            entityTypes.addAll(ruleEntityTypes);
        }
        this.rules = List.copyOf(registeredRules);
        this.presetNames = List.copyOf(presetNames);
        this.entityTypes = Set.copyOf(entityTypes);
    }

    /** Active rule IDs in execution order. */
    public List<String> ruleIds() {
        return this.rules.stream().map(RegisteredRule::ruleId).toList();
    }

    /**
     * Preset names specified when building this analyzer.
     * Rules from these presets may be excluded.
     */
    public List<String> presetNames() {
        return this.presetNames;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /** Declares the configured rules' entity types as trusted for this analyzer. */
    @Override
    public Set<String> trustedEntityTypes() {
        return this.entityTypes;
    }

    @Override
    public List<PiiSpan> analyze(String text, PiiAnalysisOptions options, PrivacyProcessingLimits limits) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(options, "options must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        PiiRuleSupport.checkInterrupted();
        if (text.length() > limits.maxTextCharacters()) {
            throw PiiRuleSupport.limitExceeded();
        }
        if (text.isBlank()) {
            return List.of();
        }
        List<PiiSpan> result = new ArrayList<>();
        for (RegisteredRule rule : this.rules) {
            PiiRuleSupport.checkInterrupted();
            int remainingSpanCount = limits.maxResultSpans() - result.size();
            List<PiiSpan> spans = analyzeWithRule(rule, text, options, limits, remainingSpanCount);
            result.addAll(spans);
        }
        return List.copyOf(result);
    }

    private static List<PiiSpan> analyzeWithRule(
        RegisteredRule rule,
        String text,
        PiiAnalysisOptions options,
        PrivacyProcessingLimits limits,
        int remainingSpanCount
    ) {
        // A zero remaining budget still permits a later rule to return no matches.
        PrivacyProcessingLimits ruleLimits = PrivacyProcessingLimits.builder()
            .maxTextCharacters(limits.maxTextCharacters())
            .maxOutputCharacters(limits.maxOutputCharacters())
            .maxValueTreeCharacters(limits.maxValueTreeCharacters())
            .maxValueTreeNodes(limits.maxValueTreeNodes())
            .maxDepth(limits.maxDepth())
            .maxAnalysisSegments(limits.maxAnalysisSegments())
            .maxResultSpans(Math.max(1, remainingSpanCount))
            .build();
        try {
            // Delegate PII detection to the configured rule.
            List<PiiSpan> spans = rule.delegate().analyze(text, options, ruleLimits);
            Objects.requireNonNull(spans);
            PiiRuleSupport.checkInterrupted();
            validateRuleResults(rule, spans, text.length(), remainingSpanCount);
            return List.copyOf(spans);
        } catch (PrivacyGuardrailException failure) {
            // Do not expose application exception messages or causes containing source text.
            throw new PrivacyGuardrailException(failure.code(), failure.phase(),
                "PII rule '" + rule.ruleId() + "' failed");
        } catch (RuntimeException failure) {
            throw new IllegalStateException("PII rule '" + rule.ruleId() + "' failed");
        }
    }

    private static void validateRuleResults(
        RegisteredRule rule,
        List<PiiSpan> spans,
        int textLength,
        int remainingSpanCount
    ) {
        if (spans.size() > remainingSpanCount) {
            throw PiiRuleSupport.limitExceeded();
        }
        for (PiiSpan span : spans) {
            if (span == null || span.end() > textLength
                || !rule.entityTypes().contains(span.entityType())) {
                throw new IllegalStateException("Invalid rule result");
            }
        }
    }

    private record RegisteredRule(String ruleId, Set<String> entityTypes, PiiRule delegate) {
    }
}
