package io.github.ultramancode.springai.privacy.autoconfigure;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailurePolicy;
import io.github.ultramancode.springai.privacy.core.PiiResolutionMode;
import io.github.ultramancode.springai.privacy.core.PiiResolutionPolicy;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Configuration for privacy processing and PII analyzers. */
@ConfigurationProperties("spring.ai.privacy")
public class PrivacyGuardrailsProperties {

    /** Configurable privacy processing limits. */
    private final Processing processing = new Processing();

    /** PII analyzer resolution settings. */
    private final Analysis analysis = new Analysis();

    /** Local PII rule selection settings. */
    private final Rules rules = new Rules();

    public Processing getProcessing() {
        return this.processing;
    }

    public Analysis getAnalysis() {
        return this.analysis;
    }

    public Rules getRules() {
        return this.rules;
    }

    /** Per-service limits for input, output, value trees, and analysis. */
    public static class Processing {
        private int maxTextCharacters = PrivacyProcessingLimits.DEFAULT_MAX_TEXT_CHARACTERS;
        private int maxOutputCharacters = PrivacyProcessingLimits.DEFAULT_MAX_OUTPUT_CHARACTERS;
        private int maxValueTreeCharacters = PrivacyProcessingLimits.DEFAULT_MAX_VALUE_TREE_CHARACTERS;
        private int maxValueTreeNodes = PrivacyProcessingLimits.DEFAULT_MAX_VALUE_TREE_NODES;
        private int maxDepth = PrivacyProcessingLimits.DEFAULT_MAX_DEPTH;
        private int maxAnalysisSegments = PrivacyProcessingLimits.DEFAULT_MAX_ANALYSIS_SEGMENTS;
        private int maxResultSpans = PrivacyProcessingLimits.DEFAULT_MAX_RESULT_SPANS;

        public int getMaxTextCharacters() {
            return this.maxTextCharacters;
        }

        public void setMaxTextCharacters(int maxTextCharacters) {
            this.maxTextCharacters = requirePositive(maxTextCharacters, "processing.max-text-characters");
        }

        public int getMaxOutputCharacters() {
            return this.maxOutputCharacters;
        }

        public void setMaxOutputCharacters(int maxOutputCharacters) {
            this.maxOutputCharacters = requirePositive(maxOutputCharacters, "processing.max-output-characters");
        }

        public int getMaxValueTreeCharacters() {
            return this.maxValueTreeCharacters;
        }

        public void setMaxValueTreeCharacters(int maxValueTreeCharacters) {
            this.maxValueTreeCharacters = requirePositive(maxValueTreeCharacters, "processing.max-value-tree-characters");
        }

        public int getMaxValueTreeNodes() {
            return this.maxValueTreeNodes;
        }

        public void setMaxValueTreeNodes(int maxValueTreeNodes) {
            this.maxValueTreeNodes = requirePositive(maxValueTreeNodes, "processing.max-value-tree-nodes");
        }

        public int getMaxDepth() {
            return this.maxDepth;
        }

        public void setMaxDepth(int maxDepth) {
            this.maxDepth = requirePositive(maxDepth, "processing.max-depth");
        }

        public int getMaxAnalysisSegments() {
            return this.maxAnalysisSegments;
        }

        public void setMaxAnalysisSegments(int maxAnalysisSegments) {
            this.maxAnalysisSegments = requirePositive(maxAnalysisSegments, "processing.max-analysis-segments");
        }

        public int getMaxResultSpans() {
            return this.maxResultSpans;
        }

        public void setMaxResultSpans(int maxResultSpans) {
            this.maxResultSpans = requirePositive(maxResultSpans, "processing.max-result-spans");
        }

        PrivacyProcessingLimits limits() {
            return new PrivacyProcessingLimits(
                    this.maxTextCharacters,
                    this.maxOutputCharacters,
                    this.maxValueTreeCharacters,
                    this.maxValueTreeNodes,
                    this.maxDepth,
                    this.maxAnalysisSegments,
                    this.maxResultSpans
            );
        }

        private static int requirePositive(int value, String property) {
            if (value <= 0) {
                throw new IllegalArgumentException(property + " must be positive");
            }
            return value;
        }
    }

    /** Properties for analyzer selection, evidence resolution, and failure handling. */
    public static class Analysis {

        /** Language code passed to analyzers. */
        private String language = PiiAnalysisOptions.DEFAULT_LANGUAGE;
        /**
         * Entity types to include in detection. An empty list includes every supported type.
         * Listing a type does not register it as trusted.
         */
        private List<String> includedEntityTypes = new ArrayList<>();
        /** Global minimum analyzer confidence score. */
        private double minimumScore = PiiAnalysisOptions.DEFAULT_MINIMUM_SCORE;
        /** Strategy used to combine evidence from multiple analyzers. */
        private PiiResolutionMode mode = PiiResolutionPolicy.DEFAULT_MODE;
        /** Provider ID treated as primary by the selected resolution mode. */
        private String primaryProvider;
        /** Providers allowed to supplement primary-provider evidence. */
        private List<String> supplementalProviders = new ArrayList<>();
        /** Sole policy controlling whether analyzer failures stop processing. */
        private PiiAnalyzerFailurePolicy failurePolicy = PiiResolutionPolicy.DEFAULT_FAILURE_POLICY;
        /**
         * Minimum confidence scores for individual providers.
         * The effective threshold is the greater of the global and provider scores.
         */
        private Map<String, Double> providerMinimumScores = new LinkedHashMap<>();
        /** Entity aliases mapped to canonical entity types. */
        private Map<String, String> entityAliases = new LinkedHashMap<>();
        /** Canonical type assigned when overlapping evidence has conflicting types. */
        private String typeConflictFallback = PiiResolutionPolicy.DEFAULT_TYPE_CONFLICT_FALLBACK;

        public String getLanguage() {
            return this.language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public List<String> getIncludedEntityTypes() {
            return this.includedEntityTypes;
        }

        public void setIncludedEntityTypes(List<String> includedEntityTypes) {
            this.includedEntityTypes = new ArrayList<>(Objects.requireNonNull(
                    includedEntityTypes,
                    "analysis.included-entity-types must not be null"
            ));
        }

        public double getMinimumScore() {
            return this.minimumScore;
        }

        public void setMinimumScore(double minimumScore) {
            this.minimumScore = minimumScore;
        }

        public PiiResolutionMode getMode() {
            return this.mode;
        }

        public void setMode(PiiResolutionMode mode) {
            this.mode = Objects.requireNonNull(mode, "analysis.mode must not be null");
        }

        public String getPrimaryProvider() {
            return this.primaryProvider;
        }

        public void setPrimaryProvider(String primaryProvider) {
            this.primaryProvider = primaryProvider;
        }

        public List<String> getSupplementalProviders() {
            return this.supplementalProviders;
        }

        public void setSupplementalProviders(List<String> supplementalProviders) {
            this.supplementalProviders = new ArrayList<>(Objects.requireNonNull(
                    supplementalProviders,
                    "analysis.supplemental-providers must not be null"
            ));
        }

        public PiiAnalyzerFailurePolicy getFailurePolicy() {
            return this.failurePolicy;
        }

        public void setFailurePolicy(PiiAnalyzerFailurePolicy failurePolicy) {
            this.failurePolicy = Objects.requireNonNull(
                    failurePolicy,
                    "analysis.failure-policy must not be null"
            );
        }

        public Map<String, Double> getProviderMinimumScores() {
            return this.providerMinimumScores;
        }

        public void setProviderMinimumScores(Map<String, Double> providerMinimumScores) {
            this.providerMinimumScores = new LinkedHashMap<>(Objects.requireNonNull(
                    providerMinimumScores,
                    "analysis.provider-minimum-scores must not be null"
            ));
        }

        public Map<String, String> getEntityAliases() {
            return this.entityAliases;
        }

        public void setEntityAliases(Map<String, String> entityAliases) {
            this.entityAliases = new LinkedHashMap<>(Objects.requireNonNull(
                    entityAliases,
                    "analysis.entity-aliases must not be null"
            ));
        }

        public String getTypeConflictFallback() {
            return this.typeConflictFallback;
        }

        public void setTypeConflictFallback(String typeConflictFallback) {
            this.typeConflictFallback = typeConflictFallback;
        }
    }

    /** Automatic configuration and selection of Java-defined rules. */
    public static class Rules {
        /** Whether to auto-configure a local RULES analyzer. Defaults to false. */
        private boolean enabled;
        /** Ordered preset names. COMMON is not implicitly included. */
        private List<String> presets = new ArrayList<>();
        /** Additional registered rule IDs to activate. */
        private List<String> include = new ArrayList<>();
        /** Registered rule IDs to remove from the combined selection. */
        private List<String> exclude = new ArrayList<>();

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getPresets() {
            return this.presets;
        }

        public void setPresets(List<String> presets) {
            this.presets = new ArrayList<>(Objects.requireNonNull(presets, "rules.presets must not be null"));
        }

        public List<String> getInclude() {
            return this.include;
        }

        public void setInclude(List<String> include) {
            this.include = new ArrayList<>(Objects.requireNonNull(include, "rules.include must not be null"));
        }

        public List<String> getExclude() {
            return this.exclude;
        }

        public void setExclude(List<String> exclude) {
            this.exclude = new ArrayList<>(Objects.requireNonNull(exclude, "rules.exclude must not be null"));
        }
    }
}
