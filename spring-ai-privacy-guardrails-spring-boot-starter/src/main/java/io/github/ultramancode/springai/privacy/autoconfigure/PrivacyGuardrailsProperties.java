package io.github.ultramancode.springai.privacy.autoconfigure;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailurePolicy;
import io.github.ultramancode.springai.privacy.core.PiiResolutionMode;
import io.github.ultramancode.springai.privacy.core.PiiResolutionPolicy;
import io.github.ultramancode.springai.privacy.core.RegexPiiRule;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAction;
import io.github.ultramancode.springai.privacy.springai.PrivacyOutputAdvisor;
import io.github.ultramancode.springai.privacy.springai.PrivacyResponseInspectionLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Configuration for the core privacy guardrails and fixed Spring AI boundary. */
@ConfigurationProperties("spring.ai.privacy")
public class PrivacyGuardrailsProperties {

    /**
     * Whether privacy infrastructure and the selectable ChatClient configurer are enabled.
     * Enabling this property does not apply the boundary to every ChatClient automatically.
     */
    private boolean enabled = false;
    /** Optional output-boundary settings. */
    private final Output output = new Output();
    /** Model-response inspection limits shared by call, stream, and tool-execution boundaries. */
    private final ResponseInspection responseInspection = new ResponseInspection();
    /** PII analyzer resolution settings. */
    private final Analysis analysis = new Analysis();
    /** Built-in regular-expression analyzer settings. */
    private final Regex regex = new Regex();
    /** Tool boundary policy settings. */
    private final Tools tools = new Tools();

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Output getOutput() {
        return this.output;
    }

    public ResponseInspection getResponseInspection() {
        return this.responseInspection;
    }

    public Analysis getAnalysis() {
        return this.analysis;
    }

    public Regex getRegex() {
        return this.regex;
    }

    public Tools getTools() {
        return this.tools;
    }

    /** Properties for optional application-facing output protection. */
    public static class Output {

        /** Whether final model-output protection is included in the configured boundary. */
        private boolean enabled = false;
        /** Action applied when output PII is detected. */
        private PrivacyOutputAction action = PrivacyOutputAdvisor.DEFAULT_ACTION;
        /** Safe exception message used when output is blocked. */
        private String blockExceptionMessage = PrivacyOutputAdvisor.DEFAULT_BLOCK_EXCEPTION_MESSAGE;
        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public PrivacyOutputAction getAction() {
            return this.action;
        }

        public void setAction(PrivacyOutputAction action) {
            this.action = Objects.requireNonNull(action, "output.action must not be null");
        }

        public String getBlockExceptionMessage() {
            return this.blockExceptionMessage;
        }

        public void setBlockExceptionMessage(String blockExceptionMessage) {
            if (blockExceptionMessage == null || blockExceptionMessage.isBlank()) {
                throw new IllegalArgumentException("output.block-exception-message must not be blank");
            }
            this.blockExceptionMessage = blockExceptionMessage;
        }

    }

    /** Properties for bounded model-response inspection across call and stream paths. */
    public static class ResponseInspection {

        /** Maximum response frames buffered before streaming inspection fails closed. */
        private int maxStreamFrames = PrivacyResponseInspectionLimits.DEFAULT_MAX_STREAM_FRAMES;
        /** Maximum text and tool-argument characters inspected per response. */
        private long maxCharacters = PrivacyResponseInspectionLimits.DEFAULT_MAX_CHARACTERS;
        /** Maximum known media bytes inspected per response. */
        private long maxMediaBytes = PrivacyResponseInspectionLimits.DEFAULT_MAX_MEDIA_BYTES;
        /** Maximum idle interval allowed between streaming response frames. */
        private Duration streamIdleTimeout = PrivacyResponseInspectionLimits.DEFAULT_STREAM_IDLE_TIMEOUT;

        public int getMaxStreamFrames() {
            return this.maxStreamFrames;
        }

        public void setMaxStreamFrames(int maxStreamFrames) {
            this.maxStreamFrames = requirePositive(
                    maxStreamFrames,
                    "response-inspection.max-stream-frames"
            );
        }

        public long getMaxCharacters() {
            return this.maxCharacters;
        }

        public void setMaxCharacters(long maxCharacters) {
            this.maxCharacters = requirePositive(
                    maxCharacters,
                    "response-inspection.max-characters"
            );
        }

        public long getMaxMediaBytes() {
            return this.maxMediaBytes;
        }

        public void setMaxMediaBytes(long maxMediaBytes) {
            this.maxMediaBytes = requirePositive(
                    maxMediaBytes,
                    "response-inspection.max-media-bytes"
            );
        }

        public Duration getStreamIdleTimeout() {
            return this.streamIdleTimeout;
        }

        public void setStreamIdleTimeout(Duration streamIdleTimeout) {
            Duration validatedTimeout = Objects.requireNonNull(
                    streamIdleTimeout,
                    "response-inspection.stream-idle-timeout must not be null"
            );
            if (validatedTimeout.isZero() || validatedTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "response-inspection.stream-idle-timeout must be positive"
                );
            }
            this.streamIdleTimeout = validatedTimeout;
        }

        PrivacyResponseInspectionLimits limits() {
            return new PrivacyResponseInspectionLimits(
                    this.maxStreamFrames,
                    this.maxCharacters,
                    this.maxMediaBytes,
                    this.streamIdleTimeout
            );
        }

        private int requirePositive(int value, String property) {
            if (value <= 0) {
                throw new IllegalArgumentException(property + " must be positive");
            }
            return value;
        }

        private long requirePositive(long value, String property) {
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
        /** Detection allowlist only; empty includes every supported type and values never register trusted types. */
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
        /** Additional per-provider confidence floors; the effective threshold is the greater of global and provider values. */
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

    /** Properties for the optional built-in Java regular-expression analyzer. */
    public static class Regex {

        /** Whether the built-in regular-expression analyzer is enabled. */
        private boolean enabled = false;
        /** Ordered rules requiring entity type and pattern, with optional score, capture group, and validator ID. */
        private List<Rule> rules = new ArrayList<>();

        public boolean isEnabled() {
            return this.enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<Rule> getRules() {
            return this.rules;
        }

        public void setRules(List<Rule> rules) {
            this.rules = new ArrayList<>(Objects.requireNonNull(rules, "regex.rules must not be null"));
        }

        /** One configured regular-expression detection rule. */
        public static class Rule {

            /** Entity type emitted by the rule. */
            private String entityType;
            /** Java regular expression used to find the entity. */
            private String pattern;
            /** Confidence score assigned to matches. */
            private double score = RegexPiiRule.DEFAULT_SCORE;
            /** Capturing group that identifies the entity text. */
            private int captureGroup = 0;
            /** Optional stable ID of a RegexPiiMatchValidator bean. */
            private String validatorId;

            public String getEntityType() {
                return this.entityType;
            }

            public void setEntityType(String entityType) {
                this.entityType = entityType;
            }

            public String getPattern() {
                return this.pattern;
            }

            public void setPattern(String pattern) {
                this.pattern = pattern;
            }

            public double getScore() {
                return this.score;
            }

            public void setScore(double score) {
                this.score = score;
            }

            public int getCaptureGroup() {
                return this.captureGroup;
            }

            public void setCaptureGroup(int captureGroup) {
                this.captureGroup = captureGroup;
            }

            public String getValidatorId() {
                return this.validatorId;
            }

            public void setValidatorId(String validatorId) {
                this.validatorId = validatorId;
            }
        }
    }

    /** Properties for exact-name, least-privilege tool disclosures. */
    public static class Tools {

        /** Exact wrapped-tool names mapped to canonical entity types they may receive as originals. */
        private Map<String, List<String>> disclosures = new LinkedHashMap<>();

        public Map<String, List<String>> getDisclosures() {
            return this.disclosures;
        }

        public void setDisclosures(Map<String, List<String>> disclosures) {
            Map<String, List<String>> copiedDisclosures = new LinkedHashMap<>();
            Objects.requireNonNull(disclosures, "tools.disclosures must not be null")
                    .forEach((toolName, entityTypes) -> copiedDisclosures.put(
                            toolName,
                            List.copyOf(Objects.requireNonNull(
                                    entityTypes,
                                    "tools.disclosures values must not be null"
                            ))
                    ));
            this.disclosures = copiedDisclosures;
        }

    }

}
