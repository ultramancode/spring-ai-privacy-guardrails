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
     * Returns the entity types a tool may receive as original values.
     *
     * @param toolDefinition definition of the tool to evaluate
     * @return non-null allowed entity types. Use {@link AllowedEntityTypes#none()} to disclose no original values
     */
    AllowedEntityTypes getAllowedEntityTypes(ToolDefinition toolDefinition);

    /**
     * Returns a policy that discloses no original values to any tool.
     *
     * @return reusable default-deny policy
     */
    static ToolDisclosurePolicy denyAll() {
        return toolDefinition -> AllowedEntityTypes.none();
    }

    /**
     * Creates a policy that allows each named tool to receive original values
     * of the specified entity types. Tool names are matched exactly and are case-sensitive.
     * Every configured tool must declare at least one entity type.
     * Omitted tools receive no original values.
     *
     * @param entityTypesByToolName tool names mapped to entity types they may receive as originals
     * @return immutable exact-name disclosure policy
     */
    static ToolDisclosurePolicy allowEntityTypesByToolName(
            Map<String, ? extends Collection<String>> entityTypesByToolName
    ) {
        Objects.requireNonNull(entityTypesByToolName, "entityTypesByToolName must not be null");
        Map<String, AllowedEntityTypes> validatedEntityTypesByToolName = new LinkedHashMap<>();
        entityTypesByToolName.forEach((toolName, entityTypes) -> {
            String validatedToolName = requireValidToolName(toolName);
            AllowedEntityTypes allowedEntityTypes = AllowedEntityTypes.of(entityTypes);
            if (allowedEntityTypes.entityTypes().isEmpty()) {
                throw new IllegalArgumentException("tool disclosures must not contain empty entity type sets");
            }
            validatedEntityTypesByToolName.put(validatedToolName, allowedEntityTypes);
        });
        Map<String, AllowedEntityTypes> allowedEntityTypesByToolName = Map.copyOf(validatedEntityTypesByToolName);
        return toolDefinition -> toolDefinition == null
                ? AllowedEntityTypes.none()
                : allowedEntityTypesByToolName.getOrDefault(toolDefinition.name(), AllowedEntityTypes.none());
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
