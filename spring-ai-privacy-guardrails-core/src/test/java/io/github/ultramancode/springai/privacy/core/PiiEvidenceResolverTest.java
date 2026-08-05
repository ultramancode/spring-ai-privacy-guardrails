package io.github.ultramancode.springai.privacy.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PiiEvidenceResolverTest {

    @Test
    void resolveConsumesCanonicalEntityAllowlistFromItsServiceCreationBoundary() {
        PiiEvidenceResolver resolver = resolverWithAliases(PiiResolutionPolicy.defaults());
        PiiAnalysisOptions canonicalOptions = PiiAnalysisOptions.builder()
                .includedEntityTypes(List.of("PERSON"))
                .build();

        List<ResolvedPiiSpan> result = resolver.resolve(
                "Alice alice@example.com",
                List.of(
                        evidence("PER", 0, 5, "OPENNLP", 0.90),
                        evidence("EMAIL", 6, 23, "REGEX", 0.90)
                ),
                Set.of("OPENNLP", "REGEX"),
                canonicalOptions
        );

        assertThat(result).singleElement().satisfies(span -> {
            assertThat(span.entityType()).isEqualTo("PERSON");
            assertThat(span.start()).isZero();
            assertThat(span.end()).isEqualTo(5);
        });
    }

    @Test
    void resolveAppliesAliasesAndKeepsSupportingEvidence() {
        PiiEvidenceResolver resolver = resolverWithAliases(PiiResolutionPolicy.defaults());

        List<ResolvedPiiSpan> result = resolver.resolve(
                "Alice",
                List.of(
                        evidence("PER", 0, 5, "OPENNLP", 0.84),
                        evidence("PERSON", 0, 5, "PRESIDIO", 0.91)
                ),
                Set.of("OPENNLP", "PRESIDIO"),
                PiiAnalysisOptions.defaults()
        );

        assertThat(result).singleElement().satisfies(span -> {
            assertThat(span.entityType()).isEqualTo("PERSON");
            assertThat(span.reason()).isEqualTo(PiiResolutionReason.EXACT_MATCH);
            assertThat(span.evidence()).extracting(PiiEvidence::provider)
                    .containsExactlyInAnyOrder("OPENNLP", "PRESIDIO");
        });
    }

    @Test
    void resolveUsesGenericTypeForSameBoundaryTypeConflict() {
        PiiEvidenceResolver resolver = resolver(PiiResolutionPolicy.defaults());

        List<ResolvedPiiSpan> result = resolver.resolve(
                "Spring",
                List.of(
                        evidence("PERSON", 0, 6, "OPENNLP", 0.85),
                        evidence("ORGANIZATION", 0, 6, "PRESIDIO", 0.90)
                ),
                Set.of("OPENNLP", "PRESIDIO"),
                PiiAnalysisOptions.defaults()
        );

        assertThat(result).singleElement().satisfies(span -> {
            assertThat(span.entityType()).isEqualTo("PII");
            assertThat(span.reason()).isEqualTo(PiiResolutionReason.TYPE_CONFLICT);
        });
    }

    @Test
    void resolveKeepsUniqueCoveringTypeInsteadOfLeakingPartialValue() {
        PiiEvidenceResolver resolver = resolver(PiiResolutionPolicy.defaults());

        List<ResolvedPiiSpan> result = resolver.resolve(
                "john@example.com",
                List.of(
                        evidence("PERSON", 0, 4, "OPENNLP", 0.99),
                        evidence("EMAIL_ADDRESS", 0, 16, "REGEX", 0.90)
                ),
                Set.of("OPENNLP", "REGEX"),
                PiiAnalysisOptions.defaults()
        );

        assertThat(result).singleElement().satisfies(span -> {
            assertThat(span.entityType()).isEqualTo("EMAIL_ADDRESS");
            assertThat(span.start()).isZero();
            assertThat(span.end()).isEqualTo(16);
            assertThat(span.reason()).isEqualTo(PiiResolutionReason.COVERING_EVIDENCE);
        });
    }

    @Test
    void resolveUnionsPartialOverlapAndAvoidsGuessingEntityType() {
        PiiEvidenceResolver resolver = resolver(PiiResolutionPolicy.defaults());

        List<ResolvedPiiSpan> result = resolver.resolve(
                "Alice Smith",
                List.of(
                        evidence("PERSON", 0, 5, "OPENNLP", 0.91),
                        evidence("ORGANIZATION", 3, 11, "PRESIDIO", 0.88)
                ),
                Set.of("OPENNLP", "PRESIDIO"),
                PiiAnalysisOptions.defaults()
        );

        assertThat(result).singleElement().satisfies(span -> {
            assertThat(span.entityType()).isEqualTo("PII");
            assertThat(span.start()).isZero();
            assertThat(span.end()).isEqualTo(11);
            assertThat(span.reason()).isEqualTo(PiiResolutionReason.TYPE_CONFLICT);
        });
    }

    @Test
    void resolveAppliesProviderSpecificThresholds() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .providerMinimumScores(Map.of("OPENNLP", 0.85, "PRESIDIO", 0.60))
                .build();
        PiiEvidenceResolver resolver = resolver(policy);

        List<ResolvedPiiSpan> result = resolver.resolve(
                "Alice Bob",
                List.of(
                        evidence("PERSON", 0, 5, "OPENNLP", 0.80),
                        evidence("PERSON", 6, 9, "PRESIDIO", 0.70)
                ),
                Set.of("OPENNLP", "PRESIDIO"),
                PiiAnalysisOptions.defaults()
        );

        assertThat(result).singleElement().satisfies(span -> {
            assertThat(span.start()).isEqualTo(6);
            assertThat(span.end()).isEqualTo(9);
        });
    }

    @Test
    void resolveUsesFallbackProvidersOnlyWhenPrimaryIsUnavailable() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY_WITH_FALLBACK)
                .primaryProvider("PRESIDIO")
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        PiiEvidenceResolver resolver = resolver(policy);
        List<PiiEvidence> evidence = List.of(
                evidence("PERSON", 0, 5, "OPENNLP", 0.90),
                evidence("PERSON", 6, 9, "PRESIDIO", 0.90)
        );

        assertThat(resolver.resolve(
                "Alice Bob", evidence, Set.of("OPENNLP", "PRESIDIO"), PiiAnalysisOptions.defaults()))
                .singleElement().satisfies(span -> {
                    assertThat(span.start()).isEqualTo(6);
                    assertThat(span.end()).isEqualTo(9);
                });
        assertThat(resolver.resolve(
                "Alice Bob",
                List.of(evidence("PERSON", 0, 5, "OPENNLP", 0.90)),
                Set.of("OPENNLP"),
                PiiAnalysisOptions.defaults()
        ))
                .singleElement().satisfies(span -> {
                    assertThat(span.start()).isZero();
                    assertThat(span.end()).isEqualTo(5);
                });
    }

    @Test
    void resolvePrimaryModeNeverFallsBackWhenPrimaryIsUnavailable() {
        PiiResolutionPolicy policy = PiiResolutionPolicy.builder()
                .mode(PiiResolutionMode.PRIMARY)
                .primaryProvider("PRESIDIO")
                .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                .build();
        PiiEvidenceResolver resolver = resolver(policy);

        assertThat(resolver.resolve(
                "Alice",
                List.of(evidence("PERSON", 0, 5, "OPENNLP", 0.90)),
                Set.of("OPENNLP"),
                PiiAnalysisOptions.defaults()
        )).isEmpty();
    }

    @Test
    void resolveRejectsEvidenceOutsideTheSourceText() {
        PiiEvidenceResolver resolver = resolver(PiiResolutionPolicy.defaults());

        assertThatThrownBy(() -> resolver.resolve(
                "Alice",
                List.of(evidence("PERSON", 0, 99, "PRESIDIO", 0.90)),
                Set.of("PRESIDIO"),
                PiiAnalysisOptions.defaults()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the source text")
                .hasMessageNotContaining("Alice");
    }

    @Test
    void resolveRejectsEvidenceAttributedToAnUnsuccessfulProvider() {
        PiiEvidenceResolver resolver = resolver(PiiResolutionPolicy.defaults());

        assertThatThrownBy(() -> resolver.resolve(
                "Alice",
                List.of(evidence("PERSON", 0, 5, "PRESIDIO", 0.90)),
                Set.of("OPENNLP"),
                PiiAnalysisOptions.defaults()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider must be successful")
                .hasMessageNotContaining("Alice");
    }

    @Test
    void resolveRejectsEvidenceWithoutAProviderEntityTypeRegistry() {
        EntityTypeRegistry registry = EntityTypeRegistry.defaults();
        PiiEvidenceResolver resolver = new PiiEvidenceResolver(
                registry,
                Map.of(),
                registry,
                PiiResolutionPolicy.defaults()
        );

        assertThatThrownBy(() -> resolver.resolve(
                "Alice",
                List.of(evidence("PERSON", 0, 5, "PRESIDIO", 0.90)),
                Set.of("PRESIDIO"),
                PiiAnalysisOptions.defaults()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("evidence provider has no entity-type registry")
                .hasMessageNotContaining("Alice");
    }

    private PiiEvidenceResolver resolver(PiiResolutionPolicy policy) {
        return resolver(EntityTypeRegistry.defaults(), policy);
    }

    private PiiEvidenceResolver resolverWithAliases(PiiResolutionPolicy policy) {
        return resolver(
                new EntityTypeRegistry(Map.of("PER", "PERSON", "EMAIL", "EMAIL_ADDRESS")),
                policy
        );
    }

    private PiiEvidenceResolver resolver(
            EntityTypeRegistry configuredRegistry,
            PiiResolutionPolicy policy
    ) {
        EntityTypeRegistry registry = configuredRegistry.withAdditionalTrustedTypes(
                Set.of(policy.typeConflictFallback())
        );
        return new PiiEvidenceResolver(
                registry,
                Map.of(
                        "OPENNLP", registry,
                        "PRESIDIO", registry,
                        "REGEX", registry
                ),
                registry,
                policy
        );
    }

    private PiiEvidence evidence(String type, int start, int end, String provider, double score) {
        return new PiiEvidence(type, start, end, provider, score);
    }
}
