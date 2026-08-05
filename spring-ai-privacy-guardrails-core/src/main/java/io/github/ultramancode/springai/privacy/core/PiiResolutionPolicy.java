package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable policy for provider selection, failure handling, thresholds, and type conflicts. */
public final class PiiResolutionPolicy {

    /** Default provider-selection mode. */
    public static final PiiResolutionMode DEFAULT_MODE = PiiResolutionMode.UNION;

    /** Default fail-closed analyzer availability policy. */
    public static final PiiAnalyzerFailurePolicy DEFAULT_FAILURE_POLICY = PiiAnalyzerFailurePolicy.REQUIRE_ALL;

    /** Default generic entity type used when evidence types conflict. */
    public static final String DEFAULT_TYPE_CONFLICT_FALLBACK = EntityTypeRegistry.GENERIC_PII_TYPE;

    private final PiiResolutionMode mode;
    private final String primaryProvider;
    private final Set<String> supplementalProviders;
    private final PiiAnalyzerFailurePolicy failurePolicy;
    private final Map<String, Double> providerMinimumScores;
    private final String typeConflictFallback;

    private PiiResolutionPolicy(Builder builder) {
        this.mode = Objects.requireNonNull(builder.mode, "mode must not be null");
        this.primaryProvider = builder.primaryProvider == null
                ? null : PiiProviderId.canonicalize(builder.primaryProvider);
        if (this.mode != PiiResolutionMode.UNION && this.primaryProvider == null) {
            throw new IllegalArgumentException("primaryProvider is required for " + this.mode);
        }
        Set<String> canonicalSupplementalProviders = new LinkedHashSet<>();
        for (String provider : builder.supplementalProviders) {
            if (!canonicalSupplementalProviders.add(PiiProviderId.canonicalize(provider))) {
                throw new IllegalArgumentException("supplemental providers contain canonical duplicates");
            }
        }
        this.supplementalProviders = Set.copyOf(canonicalSupplementalProviders);
        this.failurePolicy = Objects.requireNonNull(builder.failurePolicy, "failurePolicy must not be null");
        if (this.failurePolicy == PiiAnalyzerFailurePolicy.REQUIRE_PRIMARY && this.primaryProvider == null) {
            throw new IllegalArgumentException("primaryProvider is required for REQUIRE_PRIMARY");
        }
        if (this.primaryProvider != null && this.supplementalProviders.contains(this.primaryProvider)) {
            throw new IllegalArgumentException("primaryProvider must not also be supplemental");
        }
        if (this.mode == PiiResolutionMode.UNION && !this.supplementalProviders.isEmpty()) {
            throw new IllegalArgumentException("supplementalProviders are not used in UNION mode");
        }
        if (this.mode == PiiResolutionMode.UNION
                && this.primaryProvider != null
                && this.failurePolicy != PiiAnalyzerFailurePolicy.REQUIRE_PRIMARY) {
            throw new IllegalArgumentException("primaryProvider is not used by this UNION policy");
        }
        if (this.mode == PiiResolutionMode.PRIMARY_WITH_FALLBACK
                && this.failurePolicy != PiiAnalyzerFailurePolicy.ALLOW_PARTIAL) {
            throw new IllegalArgumentException("PRIMARY_WITH_FALLBACK requires ALLOW_PARTIAL failure policy");
        }
        Map<String, Double> thresholds = new LinkedHashMap<>();
        builder.providerMinimumScores.forEach((provider, score) -> {
            if (score == null || !Double.isFinite(score) || score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("provider minimum scores must be between 0.0 and 1.0");
            }
            if (thresholds.putIfAbsent(PiiProviderId.canonicalize(provider), score) != null) {
                throw new IllegalArgumentException("provider minimum scores contain canonical duplicates");
            }
        });
        this.providerMinimumScores = Map.copyOf(thresholds);
        if (builder.typeConflictFallback == null) {
            throw new IllegalArgumentException("typeConflictFallback must not be null");
        }
        this.typeConflictFallback = EntityTypeRegistry.requireValidEntityType(builder.typeConflictFallback);
    }

    /**
     * Returns the default union policy requiring every analyzer to succeed.
     *
     * @return the default resolution policy
     */
    public static PiiResolutionPolicy defaults() {
        return builder().build();
    }

    /**
     * Creates a builder initialized with the default resolution policy.
     *
     * @return a new resolution-policy builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the configured provider-selection strategy.
     *
     * @return resolution mode
     */
    public PiiResolutionMode mode() {
        return this.mode;
    }

    /**
     * Returns the canonical primary provider ID, when the policy defines one.
     *
     * @return primary provider ID, or {@code null} when none is configured
     */
    public String primaryProvider() {
        return this.primaryProvider;
    }

    /**
     * Returns canonical provider IDs whose evidence accompanies the primary provider.
     *
     * @return immutable supplemental-provider set
     */
    public Set<String> supplementalProviders() {
        return this.supplementalProviders;
    }

    /**
     * Returns the policy that decides which analyzer failures stop processing.
     *
     * @return analyzer failure policy
     */
    public PiiAnalyzerFailurePolicy failurePolicy() {
        return this.failurePolicy;
    }

    /**
     * Returns the generic canonical type assigned to unresolved type conflicts.
     *
     * @return canonical conflict type
     */
    public String typeConflictFallback() {
        return this.typeConflictFallback;
    }

    double minimumScore(String provider, double globalMinimumScore) {
        return Math.max(globalMinimumScore, this.providerMinimumScores.getOrDefault(
                PiiProviderId.canonicalize(provider), 0.0));
    }

    /**
     * Returns canonical provider-specific confidence thresholds. These entries
     * configure filtering only; they do not register analyzers.
     *
     * @return immutable provider-to-minimum-score map
     */
    public Map<String, Double> providerMinimumScores() {
        return this.providerMinimumScores;
    }

    /** Builds immutable {@link PiiResolutionPolicy} instances. */
    public static final class Builder {

        private PiiResolutionMode mode = DEFAULT_MODE;
        private String primaryProvider;
        private List<String> supplementalProviders = List.of();
        private PiiAnalyzerFailurePolicy failurePolicy = DEFAULT_FAILURE_POLICY;
        private Map<String, Double> providerMinimumScores = new LinkedHashMap<>();
        private String typeConflictFallback = DEFAULT_TYPE_CONFLICT_FALLBACK;

        private Builder() {
        }

        /**
         * Selects how evidence from configured providers is included.
         *
         * @param mode provider-selection strategy
         * @return this builder
         */
        public Builder mode(PiiResolutionMode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * Selects the primary provider used by primary-based modes or failure policy.
         *
         * @param primaryProvider stable provider ID, or {@code null} when the
         * selected policy does not require one
         * @return this builder
         */
        public Builder primaryProvider(String primaryProvider) {
            this.primaryProvider = primaryProvider;
            return this;
        }

        /**
         * Selects providers whose evidence is always included with the primary.
         *
         * @param supplementalProviders provider IDs; canonical duplicates are rejected
         * and the values are used only by primary-based modes
         * @return this builder
         */
        public Builder supplementalProviders(Collection<String> supplementalProviders) {
            this.supplementalProviders = new ArrayList<>(Objects.requireNonNull(
                    supplementalProviders,
                    "supplementalProviders must not be null"
            ));
            return this;
        }

        /**
         * Selects which analyzer failures stop the analysis.
         *
         * @param failurePolicy analyzer availability policy
         * @return this builder
         */
        public Builder failurePolicy(PiiAnalyzerFailurePolicy failurePolicy) {
            this.failurePolicy = failurePolicy;
            return this;
        }

        /**
         * Sets provider-specific minimum confidence scores.
         *
         * @param providerMinimumScores provider IDs mapped to values between
         * {@code 0.0} and {@code 1.0}; entries do not register analyzers
         * @return this builder
         */
        public Builder providerMinimumScores(Map<String, Double> providerMinimumScores) {
            this.providerMinimumScores = new LinkedHashMap<>(Objects.requireNonNull(
                    providerMinimumScores,
                    "providerMinimumScores must not be null"
            ));
            return this;
        }

        /**
         * Sets the generic entity type used for unresolved type conflicts.
         *
         * @param typeConflictFallback exact uppercase canonical entity label
         * @return this builder
         */
        public Builder typeConflictFallback(String typeConflictFallback) {
            this.typeConflictFallback = typeConflictFallback;
            return this;
        }

        /**
         * Validates the accumulated values and creates an immutable policy.
         *
         * @return validated resolution policy
         */
        public PiiResolutionPolicy build() {
            return new PiiResolutionPolicy(this);
        }
    }
}
