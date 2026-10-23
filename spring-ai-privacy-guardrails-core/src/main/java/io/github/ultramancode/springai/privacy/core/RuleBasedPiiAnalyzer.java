package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Executes a fixed selection of local rules as the {@code RULES} provider.
 * A rule failure fails the entire analysis call. Result limits apply across all rules.
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
        Set<String> ids = new HashSet<>();
        Set<String> types = new LinkedHashSet<>();
        List<RegisteredRule> registered = new ArrayList<>();
        for (PiiRule rule : rules) {
            Objects.requireNonNull(rule, "rule must not be null");
            String id = PiiRuleSupport.requireRuleId(rule.id());
            if (!ids.add(id)) {
                throw new IllegalArgumentException("Duplicate rule ID '" + id + "'");
            }
            Set<String> supported = Set.copyOf(rule.entityTypes());
            if (supported.isEmpty()) {
                throw new IllegalArgumentException("Rule '" + id + "' must declare entity types");
            }
            supported.forEach(EntityTypeRegistry::requireValidEntityType);
            registered.add(new RegisteredRule(id, supported, rule));
            types.addAll(supported);
        }
        this.rules = List.copyOf(registered);
        this.presetNames = List.copyOf(presetNames);
        this.entityTypes = Set.copyOf(types);
    }

    /** Active rule IDs in execution order. */
    public List<String> ruleIds() {
        return this.rules.stream().map(RegisteredRule::id).toList();
    }

    /** Explicitly selected preset names. Exclusions may remove some preset members. */
    public List<String> presetNames() {
        return this.presetNames;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

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
            int remaining = limits.maxResultSpans() - result.size();
            // A zero remaining budget still permits a later rule to return no matches.
            PrivacyProcessingLimits ruleLimits = new PrivacyProcessingLimits(
                    limits.maxTextCharacters(), limits.maxOutputCharacters(), limits.maxValueTreeCharacters(),
                    limits.maxValueTreeNodes(), limits.maxDepth(), limits.maxAnalysisSegments(),
                    Math.max(1, remaining));
            try {
                List<PiiSpan> spans = Objects.requireNonNull(rule.delegate().analyze(text, options, ruleLimits));
                PiiRuleSupport.checkInterrupted();
                if (spans.size() > remaining) {
                    throw PiiRuleSupport.limitExceeded();
                }
                for (PiiSpan span : spans) {
                    if (span == null || span.end() > text.length()
                            || !rule.entityTypes().contains(span.entityType())) {
                        throw new IllegalStateException("Invalid rule result");
                    }
                    result.add(span);
                }
            } catch (PrivacyGuardrailException failure) {
                // Do not expose application exception messages or causes containing source text.
                throw new PrivacyGuardrailException(failure.code(), failure.phase(),
                        "PII rule '" + rule.id() + "' failed");
            } catch (RuntimeException failure) {
                throw new IllegalStateException("PII rule '" + rule.id() + "' failed");
            }
        }
        return List.copyOf(result);
    }

    private record RegisteredRule(String id, Set<String> entityTypes, PiiRule delegate) {
    }
}
