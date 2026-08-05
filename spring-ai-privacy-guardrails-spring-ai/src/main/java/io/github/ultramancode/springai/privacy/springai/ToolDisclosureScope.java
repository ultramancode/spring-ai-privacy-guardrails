package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.EntityTypeRegistry;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Canonical entity types that one tool is permitted to receive as original values.
 *
 * @param entityTypes immutable canonical entity types disclosed to the tool
 */
public record ToolDisclosureScope(Set<String> entityTypes) {

    private static final ToolDisclosureScope NONE = new ToolDisclosureScope(Set.of());

    /** Validates and defensively copies the permitted canonical entity types. */
    public ToolDisclosureScope {
        Set<String> canonicalTypes = new LinkedHashSet<>();
        for (String entityType : Objects.requireNonNull(entityTypes, "entityTypes must not be null")) {
            canonicalTypes.add(EntityTypeRegistry.requireValidEntityType(entityType));
        }
        entityTypes = Set.copyOf(canonicalTypes);
    }

    /**
     * Returns the default-deny disclosure that reveals no original values.
     *
     * @return the empty disclosure
     */
    public static ToolDisclosureScope none() {
        return NONE;
    }

    /**
     * Creates a disclosure from application-supplied canonical entity types.
     *
     * @param entityTypes exact uppercase entity types the tool may receive as originals
     * @return validated immutable disclosure
     */
    public static ToolDisclosureScope of(Collection<String> entityTypes) {
        Objects.requireNonNull(entityTypes, "entityTypes must not be null");
        Set<String> canonicalTypes = new LinkedHashSet<>();
        for (String entityType : entityTypes) {
            String canonicalType = EntityTypeRegistry.requireValidEntityType(entityType);
            if (!canonicalTypes.add(canonicalType)) {
                throw new IllegalArgumentException("entityTypes contain duplicates");
            }
        }
        return new ToolDisclosureScope(canonicalTypes);
    }
}
