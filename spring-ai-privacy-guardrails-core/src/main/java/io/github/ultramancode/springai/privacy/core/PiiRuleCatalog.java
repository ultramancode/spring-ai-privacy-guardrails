package io.github.ultramancode.springai.privacy.core;

import io.github.ultramancode.springai.privacy.core.rules.BuiltInPiiRules;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable catalog of local rules and named presets. Use {@link #analyzerBuilder()}
 * to choose rules and build an analyzer.
 */
public final class PiiRuleCatalog {
    private final Map<String, PiiRule> rulesById;
    private final Map<String, PiiRulePreset> presetsByName;

    private PiiRuleCatalog(Builder builder) {
        Map<String, PiiRule> rulesById = registerRules(builder.rules);
        Map<String, PiiRulePreset> presetsByName = registerPresets(builder.presets, rulesById);

        this.rulesById = Collections.unmodifiableMap(rulesById);
        this.presetsByName = Collections.unmodifiableMap(presetsByName);
    }

    /**
     * Starts an empty catalog builder.
     * Use {@link Builder#builtIns()} to register the built-in rules and presets.
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Registered rule IDs, including rules that are not active. */
    public List<String> ruleIds() {
        return List.copyOf(this.rulesById.keySet());
    }

    /** Registered preset definitions. */
    public List<PiiRulePreset> presets() {
        return List.copyOf(this.presetsByName.values());
    }

    /** Starts a builder for a {@link RuleBasedPiiAnalyzer}. No presets or rules are selected by default. */
    public AnalyzerBuilder analyzerBuilder() {
        return new AnalyzerBuilder(this);
    }

    private static Map<String, PiiRule> registerRules(List<PiiRule> rules) {
        Map<String, PiiRule> rulesById = new LinkedHashMap<>();
        for (PiiRule rule : rules) {
            String ruleId = PiiRuleSupport.requireValidRuleId(rule.id());
            if (rulesById.putIfAbsent(ruleId, rule) != null) {
                throw new IllegalArgumentException("Duplicate rule ID '" + ruleId + "'");
            }
            if (rule.entityTypes().isEmpty()) {
                throw new IllegalArgumentException("Rule '" + ruleId + "' must declare entity types");
            }
            for (String entityType : rule.entityTypes()) {
                EntityTypeRegistry.requireValidEntityType(entityType);
            }
        }
        return rulesById;
    }

    private static Map<String, PiiRulePreset> registerPresets(
        List<PiiRulePreset> presets,
        Map<String, PiiRule> rulesById
    ) {
        Map<String, PiiRulePreset> presetsByName = new LinkedHashMap<>();
        for (PiiRulePreset preset : presets) {
            if (presetsByName.putIfAbsent(preset.name(), preset) != null) {
                throw new IllegalArgumentException("Duplicate preset name '" + preset.name() + "'");
            }
            for (String ruleId : preset.ruleIds()) {
                requireRegisteredRuleId(rulesById, ruleId);
            }
        }
        return presetsByName;
    }

    private static void requireRegisteredRuleId(Map<String, PiiRule> rulesById, String ruleId) {
        if (!rulesById.containsKey(ruleId)) {
            throw new IllegalArgumentException("Unknown rule ID '" + ruleId + "'");
        }
    }

    private RuleBasedPiiAnalyzer createAnalyzer(AnalyzerBuilder analyzerBuilder) {
        LinkedHashSet<String> ruleIds = new LinkedHashSet<>();
        LinkedHashSet<String> presetNames = new LinkedHashSet<>();
        for (String presetName : analyzerBuilder.presetNames) {
            PiiRulePreset preset = this.presetsByName.get(presetName);
            if (preset == null) {
                throw new IllegalArgumentException("Unknown preset '" + presetName + "'");
            }
            presetNames.add(presetName);
            ruleIds.addAll(preset.ruleIds());
        }
        for (String ruleId : analyzerBuilder.includedRuleIds) {
            requireRegisteredRuleId(this.rulesById, ruleId);
            ruleIds.add(ruleId);
        }
        for (String ruleId : analyzerBuilder.excludedRuleIds) {
            requireRegisteredRuleId(this.rulesById, ruleId);
            ruleIds.remove(ruleId);
        }
        List<PiiRule> rules = ruleIds.stream()
            .map(this.rulesById::get)
            .toList();
        return new RuleBasedPiiAnalyzer(rules, List.copyOf(presetNames));
    }

    /** Collects definitions before creating an immutable catalog. */
    public static final class Builder {
        private final List<PiiRule> rules = new ArrayList<>();
        private final List<PiiRulePreset> presets = new ArrayList<>();

        private Builder() {
        }

        /** Registers the built-in rules and presets. */
        public Builder builtIns() {
            this.rules.addAll(BuiltInPiiRules.rules());
            this.presets.addAll(BuiltInPiiRules.presets());
            return this;
        }

        /**
         * Registers the built-in rules and their presets, overriding scores for the given rule IDs.
         * Unspecified rules retain their default scores. Scores must be finite and between 0.0 and 1.0.
         * Unknown built-in rule IDs are rejected. Application rules are unaffected.
         */
        public Builder builtInsWithScores(Map<String, Double> scoreOverrides) {
            this.rules.addAll(BuiltInPiiRules.rulesWithScores(scoreOverrides));
            this.presets.addAll(BuiltInPiiRules.presets());
            return this;
        }

        /** Registers a rule without activating it. */
        public Builder addRule(PiiRule rule) {
            this.rules.add(Objects.requireNonNull(rule, "rule must not be null"));
            return this;
        }

        /**
         * Registers rules in collection iteration order, after any rules already registered.
         * Copies the collection. Registration does not activate the rules.
         */
        public Builder addRules(Collection<? extends PiiRule> rules) {
            this.rules.addAll(List.copyOf(rules));
            return this;
        }

        /**
         * Registers a named list of rule IDs without activating it.
         * Preset names must be unique within the catalog.
         */
        public Builder addPreset(PiiRulePreset preset) {
            this.presets.add(Objects.requireNonNull(preset, "preset must not be null"));
            return this;
        }

        /** Creates an immutable catalog, validating registered rules and all preset references. */
        public PiiRuleCatalog build() {
            return new PiiRuleCatalog(this);
        }
    }

    /**
     * Builds a rule-based analyzer using presets and rule IDs registered in a catalog.
     * No presets or rules are selected by default.
     * Each configuration method copies its input and replaces the values previously set by that method.
     */
    public static final class AnalyzerBuilder {
        private final PiiRuleCatalog catalog;
        private List<String> presetNames = List.of();
        private List<String> includedRuleIds = List.of();
        private List<String> excludedRuleIds = List.of();

        private AnalyzerBuilder(PiiRuleCatalog catalog) {
            this.catalog = catalog;
        }

        /** Sets the presets to use, in the given order. */
        public AnalyzerBuilder presets(String... presetNames) {
            return presets(List.of(presetNames));
        }

        /** Sets the presets to use, in collection iteration order. */
        public AnalyzerBuilder presets(Collection<String> presetNames) {
            this.presetNames = List.copyOf(presetNames);
            return this;
        }

        /** Sets rule IDs to use after the rules from the specified presets. */
        public AnalyzerBuilder include(String... ruleIds) {
            return include(List.of(ruleIds));
        }

        /** Sets rule IDs to use in iteration order after the rules from the specified presets. */
        public AnalyzerBuilder include(Collection<String> ruleIds) {
            this.includedRuleIds = List.copyOf(ruleIds);
            return this;
        }

        /** Sets rule IDs to exclude from the analyzer. */
        public AnalyzerBuilder exclude(String... ruleIds) {
            return exclude(List.of(ruleIds));
        }

        /** Sets rule IDs to exclude from the analyzer. */
        public AnalyzerBuilder exclude(Collection<String> ruleIds) {
            this.excludedRuleIds = List.copyOf(ruleIds);
            return this;
        }

        /**
         * Creates an analyzer using rules from the specified presets in order, followed by included rules.
         * Rules with the same ID are included only once. Excluded rules are omitted.
         * All specified preset names and rule IDs must be registered in the catalog.
         * At least one rule must remain after exclusions.
         */
        public RuleBasedPiiAnalyzer build() {
            return this.catalog.createAnalyzer(this);
        }
    }
}
