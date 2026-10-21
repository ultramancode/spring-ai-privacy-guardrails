package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.EntityTypeRegistry;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical entity types that one tool is permitted to receive as original values.
 *
 * @param entityTypes immutable canonical entity types the tool may receive as original values
 */
public record AllowedEntityTypes(Set<String> entityTypes) {

    private static final AllowedEntityTypes NONE = new AllowedEntityTypes(Set.of());

    /** Validates and defensively copies the permitted canonical entity types. */
    public AllowedEntityTypes {
        Set<String> canonicalTypes = new LinkedHashSet<>();
        for (String entityType : Objects.requireNonNull(entityTypes, "entityTypes must not be null")) {
            canonicalTypes.add(EntityTypeRegistry.requireValidEntityType(entityType));
        }
        entityTypes = Set.copyOf(canonicalTypes);
    }

    /**
     * Returns an empty instance that permits no original values.
     *
     * @return empty allowed entity types
     */
    public static AllowedEntityTypes none() {
        return NONE;
    }

    /**
     * Creates allowed entity types from application-supplied canonical names.
     *
     * @param entityTypes exact uppercase entity types the tool may receive as originals
     * @return validated immutable allowed entity types
     * @throws IllegalArgumentException if an entity type is invalid or appears more than once
     * @throws NullPointerException if {@code entityTypes} is null
     */
    public static AllowedEntityTypes of(Collection<String> entityTypes) {
        Objects.requireNonNull(entityTypes, "entityTypes must not be null");
        Set<String> uniqueEntityTypes = new LinkedHashSet<>();
        for (String entityType : entityTypes) {
            if (!uniqueEntityTypes.add(entityType)) {
                throw new IllegalArgumentException("entityTypes contain duplicates");
            }
        }
        return new AllowedEntityTypes(uniqueEntityTypes);
    }
}
