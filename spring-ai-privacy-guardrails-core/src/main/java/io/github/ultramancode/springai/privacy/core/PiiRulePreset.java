package io.github.ultramancode.springai.privacy.core;

import java.util.List;
import java.util.Objects;

/** An ordered, immutable list of rule IDs. Presets cannot contain other presets. */
public record PiiRulePreset(String name, List<String> ruleIds) {
    /** Validates the name and copies the member list. References are checked by the catalog. */
    public PiiRulePreset {
        name = PiiRuleSupport.requireValidPresetName(name);
        ruleIds = List.copyOf(Objects.requireNonNull(ruleIds, "ruleIds must not be null"));
        ruleIds.forEach(PiiRuleSupport::requireValidRuleId);
    }
}
