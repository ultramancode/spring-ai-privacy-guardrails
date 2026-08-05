package io.github.ultramancode.springai.privacy.springai;

import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Decides which canonical entity types a wrapped tool may receive as original values. */
@FunctionalInterface
public interface ToolDisclosurePolicy {

    /**
     * Decides which original entity types one exact tool may receive.
     *
     * @param toolDefinition immutable definition of the tool about to be wrapped
     * @return disclosure scope; use {@link ToolDisclosureScope#none()} for default deny
     */
    ToolDisclosureScope scopeFor(ToolDefinition toolDefinition);

    /**
     * Returns a policy that discloses no original values to any tool.
     *
     * @return reusable default-deny policy
     */
    static ToolDisclosurePolicy denyAll() {
        return toolDefinition -> ToolDisclosureScope.none();
    }

    /**
     * Creates a least-privilege policy for exact, case-sensitive tool names.
     * Every configured tool must declare at least one entity type; omitted tools
     * receive no original values.
     *
     * @param entityTypesByToolName tool names mapped to entity types they may receive as originals
     * @return immutable exact-name disclosure policy
     */
    static ToolDisclosurePolicy byToolName(
            Map<String, ? extends Collection<String>> entityTypesByToolName
    ) {
        Objects.requireNonNull(entityTypesByToolName, "entityTypesByToolName must not be null");
        Map<String, ToolDisclosureScope> validated = new LinkedHashMap<>();
        entityTypesByToolName.forEach((toolName, entityTypes) -> {
            String validatedToolName = requireValidToolName(toolName);
            ToolDisclosureScope scope = ToolDisclosureScope.of(entityTypes);
            if (scope.entityTypes().isEmpty()) {
                throw new IllegalArgumentException("tool disclosures must not contain empty entity type sets");
            }
            validated.put(validatedToolName, scope);
        });
        Map<String, ToolDisclosureScope> immutable = Map.copyOf(validated);
        return toolDefinition -> toolDefinition == null
                ? ToolDisclosureScope.none()
                : immutable.getOrDefault(toolDefinition.name(), ToolDisclosureScope.none());
    }

    private static String requireValidToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("tool disclosure names must not contain null or blank values");
        }
        if (toolName.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("tool disclosure names must not contain whitespace");
        }
        return toolName;
    }
}
