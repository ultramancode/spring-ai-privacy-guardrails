package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityTypeRegistryTest {

    @Test
    void resolveAnalyzerTypeDowngradesUnknownWellFormedLabelsToGenericPii() {
        EntityTypeRegistry registry = EntityTypeRegistry.defaults();

        assertThat(registry.resolveAnalyzerType("CUSTOM_PERSON_LABEL")).isEqualTo("PII");
        assertThatThrownBy(() -> registry.resolveAnalyzerType("custom-person-label"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
    }

    @Test
    void resolveAnalyzerTypeKeepsDefaultAndLocallyRegisteredCanonicalTypes() {
        EntityTypeRegistry registry = new EntityTypeRegistry(
                Map.of(),
                Set.of("CUSTOMER_ID")
        );

        assertThat(registry.resolveAnalyzerType("PERSON")).isEqualTo("PERSON");
        assertThat(registry.resolveAnalyzerType("CUSTOMER_ID")).isEqualTo("CUSTOMER_ID");
    }

    @Test
    void defaultsDoNotGuessProviderSpecificAliases() {
        EntityTypeRegistry registry = EntityTypeRegistry.defaults();

        assertThat(registry.resolveAnalyzerType("PER")).isEqualTo("PII");
        assertThat(registry.resolveAnalyzerType("US_SSN")).isEqualTo("PII");
        assertThat(registry.resolveAnalyzerType("CREDIT_CARD_NUMBER")).isEqualTo("PII");
    }

    @Test
    void explicitAliasesResolveToOneTerminalCanonicalType() {
        EntityTypeRegistry registry = new EntityTypeRegistry(Map.of(
                "STAFF_NAME", "NAME",
                "NAME", "PERSON"
        ));

        assertThat(registry.resolveAnalyzerType("STAFF_NAME")).isEqualTo("PERSON");
    }

    @Test
    void constructorRejectsNonCanonicalConfiguredTypesInsteadOfCorrectingThem() {
        assertThatThrownBy(() -> new EntityTypeRegistry(Map.of(" per ", "ORGANIZATION")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
        assertThatThrownBy(() -> new EntityTypeRegistry(Map.of("PER", "organization")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
        assertThatThrownBy(() -> new EntityTypeRegistry(Map.of(), Set.of("customer-id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uppercase ASCII");
        assertThatThrownBy(() -> EntityTypeRegistry.requireValidEntityType("_PERSON"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single underscores");
        assertThatThrownBy(() -> EntityTypeRegistry.requireValidEntityType("PERSON_"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single underscores");
        assertThatThrownBy(() -> EntityTypeRegistry.requireValidEntityType("PERSON__NAME"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single underscores");
    }

    @Test
    void constructorRejectsAliasCycles() {
        assertThatThrownBy(() -> new EntityTypeRegistry(Map.of(
                "PERSON", "NAME",
                "NAME", "PERSON"
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycles");
    }
}
