package io.github.ultramancode.springai.privacy.core;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Owns entity-type syntax, trusted canonical types, and explicit analyzer aliases. */
public final class EntityTypeRegistry {

    static final int MAX_ENTITY_TYPE_LENGTH = 128;
    static final String ENTITY_TYPE_REGEX = "[A-Z0-9]+(?:_[A-Z0-9]+)*";
    static final String GENERIC_PII_TYPE = "PII";

    private static final Pattern ENTITY_TYPE_PATTERN = Pattern.compile(ENTITY_TYPE_REGEX);
    private static final Set<String> DEFAULT_CANONICAL_TYPES = Set.of(
            GENERIC_PII_TYPE,
            "PERSON",
            "ORGANIZATION",
            "LOCATION",
            "EMAIL_ADDRESS",
            "PHONE_NUMBER",
            "NATIONAL_ID",
            "CREDIT_CARD",
            "DATE_TIME",
            "IP_ADDRESS",
            "URL",
            "IBAN_CODE",
            "CRYPTO",
            "NRP",
            "MEDICAL_LICENSE"
    );

    private final Map<String, String> aliases;
    private final Set<String> trustedCanonicalTypes;

    /**
     * Creates a registry with explicit aliases and the library's built-in
     * canonical entity types.
     *
     * @param aliases analyzer label to canonical entity-type mappings; keys and
     * values must use exact canonical label syntax before alias chains are resolved
     */
    public EntityTypeRegistry(Map<String, String> aliases) {
        this(aliases, Set.of());
    }

    /**
     * Creates a registry with explicit aliases and additional application-trusted
     * canonical types.
     *
     * @param aliases exact uppercase analyzer-label to canonical entity-type mappings
     * @param trustedCanonicalTypes additional exact uppercase canonical labels
     * that analyzers may emit without being downgraded to the generic conflict type
     */
    public EntityTypeRegistry(Map<String, String> aliases, Set<String> trustedCanonicalTypes) {
        Map<String, String> configuredMappings = new LinkedHashMap<>();
        Objects.requireNonNull(aliases, "aliases must not be null").forEach((sourceType, targetType) -> {
            if (sourceType == null || targetType == null) {
                throw new IllegalArgumentException("entity aliases must not contain null keys or values");
            }
            configuredMappings.put(
                    requireValidEntityType(sourceType),
                    requireValidEntityType(targetType)
            );
        });
        Map<String, String> resolvedAliases = new LinkedHashMap<>();
        configuredMappings.keySet().forEach(sourceType -> resolvedAliases.put(
                sourceType,
                resolveAlias(sourceType, configuredMappings)
        ));
        this.aliases = Map.copyOf(resolvedAliases);
        Set<String> trusted = new LinkedHashSet<>(DEFAULT_CANONICAL_TYPES);
        trusted.addAll(resolvedAliases.values());
        for (String entityType : Objects.requireNonNull(
                trustedCanonicalTypes,
                "trustedCanonicalTypes must not be null"
        )) {
            trusted.add(requireValidEntityType(entityType));
        }
        this.trustedCanonicalTypes = Set.copyOf(trusted);
    }

    private static String resolveAlias(String alias, Map<String, String> aliases) {
        Set<String> visited = new LinkedHashSet<>();
        String current = alias;
        while (aliases.containsKey(current)) {
            if (!visited.add(current)) {
                throw new IllegalArgumentException("entity aliases must not contain cycles");
            }
            current = aliases.get(current);
        }
        return current;
    }

    /**
     * Returns a registry that trusts only the library's exact canonical vocabulary.
     *
     * @return the default entity-type registry
     */
    public static EntityTypeRegistry defaults() {
        return new EntityTypeRegistry(Map.of());
    }

    /**
     * Requires the exact uppercase entity-type grammar shared by analyzer
     * evidence, policy, tool disclosure, and opaque tokens.
     *
     * @param entityType entity type to validate
     * @return the unchanged validated entity type
     */
    public static String requireValidEntityType(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entityType must not be blank");
        }
        if (entityType.length() > MAX_ENTITY_TYPE_LENGTH) {
            throw new IllegalArgumentException("entityType is too long");
        }
        if (!ENTITY_TYPE_PATTERN.matcher(entityType).matches()) {
            throw new IllegalArgumentException(
                    "entityType must use uppercase ASCII letters and digits "
                            + "separated by single underscores"
            );
        }
        return entityType;
    }

    /**
     * Resolves an analyzer label to a trusted canonical type. Unknown or untrusted
     * labels resolve to the generic {@code PII} type.
     *
     * @param entityType analyzer-provided entity label
     * @return a trusted canonical entity type
     */
    public String resolveAnalyzerType(String entityType) {
        String reportedType = requireValidEntityType(entityType);
        String canonical = this.aliases.getOrDefault(reportedType, reportedType);
        return this.trustedCanonicalTypes.contains(canonical)
                ? canonical : GENERIC_PII_TYPE;
    }

    String requireTrustedType(String entityType) {
        String configuredType = requireValidEntityType(entityType);
        String canonical = this.aliases.getOrDefault(configuredType, configuredType);
        if (!this.trustedCanonicalTypes.contains(canonical)) {
            throw new IllegalArgumentException("entity filter contains an untrusted type " + configuredType);
        }
        return canonical;
    }

    EntityTypeRegistry withAdditionalTrustedTypes(Set<String> types) {
        Set<String> trusted = new LinkedHashSet<>(this.trustedCanonicalTypes);
        trusted.addAll(Objects.requireNonNull(types, "types must not be null"));
        return new EntityTypeRegistry(this.aliases, trusted);
    }
}
