package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registry of local rules and named presets. Call {@link #select(List, List, List)}
 * to create an analyzer for a selection. Registry membership is immutable.
 */
public final class PiiRuleCatalog {
    private final Map<String, PiiRule> rules;
    private final Map<String, PiiRulePreset> presets;

    private PiiRuleCatalog(Builder builder) {
        Map<String, PiiRule> registered = new LinkedHashMap<>();
        for (PiiRule rule : builder.rules) {
            String id = PiiRuleSupport.requireRuleId(rule.id());
            if (registered.putIfAbsent(id, rule) != null) {
                throw new IllegalArgumentException("Duplicate rule ID '" + id + "'");
            }
            if (rule.entityTypes().isEmpty()) {
                throw new IllegalArgumentException("Rule '" + id + "' must declare entity types");
            }
            rule.entityTypes().forEach(EntityTypeRegistry::requireValidEntityType);
        }
        Map<String, PiiRulePreset> named = new LinkedHashMap<>();
        for (PiiRulePreset preset : builder.presets) {
            if (named.putIfAbsent(preset.name(), preset) != null) {
                throw new IllegalArgumentException("Duplicate preset name '" + preset.name() + "'");
            }
            for (String id : preset.ruleIds()) {
                requireKnown(registered, id);
            }
        }
        this.rules = Collections.unmodifiableMap(registered);
        this.presets = Collections.unmodifiableMap(named);
    }

    /** Starts an empty catalog. Use {@link Builder#builtIns()} to register the built-in definitions. */
    public static Builder builder() {
        return new Builder();
    }

    /** Registered rule IDs, including rules that are not active. */
    public List<String> ruleIds() {
        return List.copyOf(this.rules.keySet());
    }

    /** Registered preset definitions. */
    public List<PiiRulePreset> presets() {
        return List.copyOf(this.presets.values());
    }

    /**
     * Expands presets in order, appends includes, deduplicates IDs, then applies exclusions.
     * Every reference must exist, including excluded IDs. An empty selection is an error.
     */
    public RuleBasedPiiAnalyzer select(List<String> presetNames, List<String> include, List<String> exclude) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        LinkedHashSet<String> selectedPresets = new LinkedHashSet<>();
        for (String name : Objects.requireNonNull(presetNames, "presetNames must not be null")) {
            PiiRuleSupport.requirePresetName(name);
            PiiRulePreset preset = this.presets.get(name);
            if (preset == null) {
                throw new IllegalArgumentException("Unknown preset '" + name + "'");
            }
            selectedPresets.add(name);
            selected.addAll(preset.ruleIds());
        }
        for (String id : Objects.requireNonNull(include, "include must not be null")) {
            requireKnown(this.rules, id);
            selected.add(id);
        }
        for (String id : Objects.requireNonNull(exclude, "exclude must not be null")) {
            requireKnown(this.rules, id);
            selected.remove(id);
        }
        return new RuleBasedPiiAnalyzer(selected.stream().map(this.rules::get).toList(),
                List.copyOf(selectedPresets));
    }

    private static void requireKnown(Map<String, PiiRule> rules, String id) {
        PiiRuleSupport.requireRuleId(id);
        if (!rules.containsKey(id)) {
            throw new IllegalArgumentException("Unknown rule ID '" + id + "'");
        }
    }

    /** Collects definitions before creating an immutable catalog. */
    public static final class Builder {
        private final List<PiiRule> rules = new ArrayList<>();
        private final List<PiiRulePreset> presets = new ArrayList<>();

        private Builder() {
        }

        /** Registers the built-in COMMON and KR rules and presets. */
        public Builder builtIns() {
            this.rules.addAll(BuiltInPiiRules.rules());
            this.presets.addAll(BuiltInPiiRules.presets());
            return this;
        }

        /** Registers a rule without activating it. */
        public Builder rule(PiiRule rule) {
            this.rules.add(Objects.requireNonNull(rule, "rule must not be null"));
            return this;
        }

        /** Registers a named list of rule IDs without activating it. */
        public Builder preset(PiiRulePreset preset) {
            this.presets.add(Objects.requireNonNull(preset, "preset must not be null"));
            return this;
        }

        /** Validates IDs and references, including references in unselected presets. */
        public PiiRuleCatalog build() {
            return new PiiRuleCatalog(this);
        }
    }
}
