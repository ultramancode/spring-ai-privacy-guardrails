package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves overlapping multi-provider evidence according to a resolution policy.
 * Entity filters supplied to this package-private boundary must already be canonical.
 */
final class PiiEvidenceResolver {

    private static final String SUPPLIED_SPAN_PROVIDER = "EXTERNAL";

    private final EntityTypeRegistry entityTypeRegistry;
    private final Map<String, EntityTypeRegistry> providerEntityTypeRegistries;
    private final EntityTypeRegistry suppliedSpanEntityTypeRegistry;
    private final PiiResolutionPolicy policy;

    PiiEvidenceResolver(
            EntityTypeRegistry entityTypeRegistry,
            Map<String, EntityTypeRegistry> providerEntityTypeRegistries,
            EntityTypeRegistry suppliedSpanEntityTypeRegistry,
            PiiResolutionPolicy policy
    ) {
        this.entityTypeRegistry = Objects.requireNonNull(
                entityTypeRegistry,
                "entityTypeRegistry must not be null"
        );
        this.providerEntityTypeRegistries = Map.copyOf(Objects.requireNonNull(
                providerEntityTypeRegistries,
                "providerEntityTypeRegistries must not be null"
        ));
        this.suppliedSpanEntityTypeRegistry = Objects.requireNonNull(
                suppliedSpanEntityTypeRegistry,
                "suppliedSpanEntityTypeRegistry must not be null"
        );
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    List<ResolvedPiiSpan> resolve(
            String text,
            List<PiiEvidence> evidence,
            Set<String> successfulProviders,
            PiiAnalysisOptions canonicalOptions
    ) {
        Objects.requireNonNull(evidence, "evidence must not be null");
        Objects.requireNonNull(successfulProviders, "successfulProviders must not be null");
        PiiAnalysisOptions options = Objects.requireNonNull(
                canonicalOptions,
                "canonicalOptions must not be null"
        );
        if (evidence.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("evidence must not contain null values");
        }
        Set<String> successful = canonicalizeProviderIds(successfulProviders);
        if (evidence.stream().anyMatch(item -> !successful.contains(item.provider()))) {
            throw new IllegalArgumentException("evidence provider must be successful");
        }
        Set<String> selectedProviders = selectedProviders(successful);
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (evidence.isEmpty()) {
            return List.of();
        }

        List<PiiEvidence> candidates = canonicalizeAndFilterCandidates(text, evidence, options).stream()
                .filter(item -> selectedProviders.contains(item.provider()))
                .filter(item -> item.score() >= this.policy.minimumScore(
                        item.provider(), options.minimumScore()))
                .sorted(Comparator.comparingInt(PiiEvidence::start).thenComparingInt(PiiEvidence::end))
                .toList();
        return resolveCandidates(candidates);
    }

    List<ResolvedPiiSpan> resolveSuppliedSpans(
            String text,
            List<PiiSpan> spans,
            PiiAnalysisOptions canonicalOptions
    ) {
        Objects.requireNonNull(spans, "spans must not be null");
        if (spans.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("spans must not contain null values");
        }
        PiiAnalysisOptions options = Objects.requireNonNull(
                canonicalOptions,
                "canonicalOptions must not be null"
        );
        if (spans.isEmpty()) {
            return List.of();
        }
        if (text == null) {
            throw new IllegalArgumentException("source text must not be null when spans are provided");
        }

        List<PiiEvidence> evidence = spans.stream()
                .map(span -> PiiEvidence.from(span, SUPPLIED_SPAN_PROVIDER))
                .toList();
        List<PiiEvidence> candidates = canonicalizeAndFilterCandidates(text, evidence, options).stream()
                .filter(item -> item.score() >= options.minimumScore())
                .sorted(Comparator.comparingInt(PiiEvidence::start).thenComparingInt(PiiEvidence::end))
                .toList();
        return resolveCandidates(candidates);
    }

    private List<PiiEvidence> canonicalizeAndFilterCandidates(
            String text,
            List<PiiEvidence> evidence,
            PiiAnalysisOptions canonicalOptions
    ) {
        return evidence.stream()
                .map(item -> validateRange(text, item))
                .map(this::resolveEntityType)
                .filter(item -> acceptsEntityType(item.entityType(), canonicalOptions))
                .toList();
    }

    private List<ResolvedPiiSpan> resolveCandidates(List<PiiEvidence> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<ResolvedPiiSpan> resolved = new ArrayList<>();
        List<PiiEvidence> cluster = new ArrayList<>();
        int clusterEnd = -1;
        for (PiiEvidence candidate : candidates) {
            if (cluster.isEmpty() || candidate.start() < clusterEnd) {
                cluster.add(candidate);
                clusterEnd = Math.max(clusterEnd, candidate.end());
                continue;
            }
            resolved.add(resolveCluster(cluster));
            cluster.clear();
            cluster.add(candidate);
            clusterEnd = candidate.end();
        }
        if (!cluster.isEmpty()) {
            resolved.add(resolveCluster(cluster));
        }
        return List.copyOf(resolved);
    }

    private PiiEvidence validateRange(String text, PiiEvidence evidence) {
        if (evidence.end() > text.length()) {
            throw new IllegalArgumentException("PII evidence is outside the source text");
        }
        return evidence;
    }

    private Set<String> canonicalizeProviderIds(Set<String> providers) {
        Set<String> successful = new LinkedHashSet<>();
        for (String provider : providers) {
            if (!successful.add(PiiProviderId.canonicalize(provider))) {
                throw new IllegalArgumentException("successfulProviders contain canonical duplicates");
            }
        }
        return Set.copyOf(successful);
    }

    private Set<String> selectedProviders(Set<String> successful) {
        if (this.policy.mode() == PiiResolutionMode.UNION) {
            return successful;
        }

        Set<String> selected = new LinkedHashSet<>(this.policy.supplementalProviders());
        String primary = this.policy.primaryProvider();
        if (successful.contains(primary)) {
            selected.add(primary);
            return Set.copyOf(selected);
        }
        if (this.policy.mode() == PiiResolutionMode.PRIMARY_WITH_FALLBACK) {
            selected.addAll(successful);
        }
        return Set.copyOf(selected);
    }

    private PiiEvidence resolveEntityType(PiiEvidence evidence) {
        EntityTypeRegistry providerRegistry = evidence.provider().equals(SUPPLIED_SPAN_PROVIDER)
                ? this.suppliedSpanEntityTypeRegistry
                : this.providerEntityTypeRegistries.get(evidence.provider());
        if (providerRegistry == null) {
            throw new IllegalArgumentException("evidence provider has no entity-type registry");
        }
        return new PiiEvidence(
                providerRegistry.resolveAnalyzerType(evidence.entityType()),
                evidence.start(),
                evidence.end(),
                evidence.provider(),
                evidence.score()
        );
    }

    private boolean acceptsEntityType(String entityType, PiiAnalysisOptions canonicalOptions) {
        if (canonicalOptions.includedEntityTypes().isEmpty()) {
            return true;
        }
        return canonicalOptions.includedEntityTypes().contains(entityType);
    }

    private ResolvedPiiSpan resolveCluster(List<PiiEvidence> cluster) {
        int start = cluster.stream().mapToInt(PiiEvidence::start).min().orElseThrow();
        int end = cluster.stream().mapToInt(PiiEvidence::end).max().orElseThrow();
        Set<String> clusterEntityTypes = cluster.stream()
                .map(PiiEvidence::entityType)
                .collect(Collectors.toSet());
        Set<String> coveringEntityTypes = cluster.stream()
                .filter(item -> item.start() == start && item.end() == end)
                .map(PiiEvidence::entityType)
                .collect(Collectors.toSet());
        boolean hasUnambiguousCoveringType = coveringEntityTypes.size() == 1;
        String entityType = hasUnambiguousCoveringType
                ? coveringEntityTypes.iterator().next()
                : clusterEntityTypes.size() == 1
                        ? clusterEntityTypes.iterator().next()
                        : this.entityTypeRegistry.resolveAnalyzerType(this.policy.typeConflictFallback());

        PiiResolutionReason reason;
        if (cluster.size() == 1) {
            reason = PiiResolutionReason.SINGLE_EVIDENCE;
        } else if (cluster.stream().allMatch(item -> item.start() == start && item.end() == end)) {
            reason = clusterEntityTypes.size() == 1
                    ? PiiResolutionReason.EXACT_MATCH
                    : PiiResolutionReason.TYPE_CONFLICT;
        } else if (hasUnambiguousCoveringType) {
            reason = PiiResolutionReason.COVERING_EVIDENCE;
        } else if (clusterEntityTypes.size() > 1) {
            reason = PiiResolutionReason.TYPE_CONFLICT;
        } else {
            reason = PiiResolutionReason.OVERLAP_UNION;
        }
        return new ResolvedPiiSpan(entityType, start, end, cluster, reason);
    }
}
