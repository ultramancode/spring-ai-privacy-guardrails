package io.github.ultramancode.springai.privacy.core;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Public facade for PII analysis, transformation, and privacy session lifecycle. */
public final class PrivacyService {

    private final PrivacyProcessingLimits processingLimits;
    private final PiiAnalysisCoordinator analysisCoordinator;
    private final SessionAnalysis sessionAnalysis;
    private final PrivacyContextRegistry contextRegistry;
    private final PrivacyTextTransformer textTransformer;
    private final PrivacyValueTreeTransformer valueTreeTransformer;

    /**
     * Creates a service with the default entity registry, resolution policy, and processing limits.
     *
     * @param analyzers analyzer instances shared across requests
     * @param options analysis language, included entity types, and score options
     */
    public PrivacyService(List<PiiAnalyzer> analyzers, PiiAnalysisOptions options) {
        this(
                analyzers,
                options,
                EntityTypeRegistry.defaults(),
                PiiResolutionPolicy.defaults(),
                PiiAnalyzerFailureObserver.noop(),
                PrivacyProcessingLimits.defaults()
        );
    }

    /**
     * Creates a service with explicit entity and resolution policies and default processing limits.
     *
     * @param analyzers analyzer instances shared across requests
     * @param options analysis language, included entity types, and score options
     * @param entityTypeRegistry canonical entity aliases and configured entity types
     * @param resolutionPolicy provider, failure, overlap, and conflict policy
     */
    public PrivacyService(
            List<PiiAnalyzer> analyzers,
            PiiAnalysisOptions options,
            EntityTypeRegistry entityTypeRegistry,
            PiiResolutionPolicy resolutionPolicy
    ) {
        this(analyzers, options, entityTypeRegistry, resolutionPolicy,
                PiiAnalyzerFailureObserver.noop(), PrivacyProcessingLimits.defaults());
    }

    /**
     * Creates a service with explicit processing limits and the default entity and resolution policies.
     *
     * @param analyzers analyzer instances shared across requests
     * @param options analysis language, included entity types, and score options
     * @param processingLimits limits for input, output, value trees, and analysis
     */
    public PrivacyService(
            List<PiiAnalyzer> analyzers,
            PiiAnalysisOptions options,
            PrivacyProcessingLimits processingLimits
    ) {
        this(analyzers, options, EntityTypeRegistry.defaults(), PiiResolutionPolicy.defaults(),
                PiiAnalyzerFailureObserver.noop(), processingLimits);
    }

    /**
     * Creates a service with explicit policies, an analyzer failure observer, and processing limits.
     *
     * @param analyzers analyzer instances shared across requests
     * @param options analysis language, included entity types, and score options
     * @param entityTypeRegistry canonical entity aliases and configured entity types
     * @param resolutionPolicy provider, failure, overlap, and conflict policy
     * @param failureObserver observer for sanitized analyzer failure events
     * @param processingLimits limits for input, output, value trees, and analysis
     */
    public PrivacyService(
            List<PiiAnalyzer> analyzers,
            PiiAnalysisOptions options,
            EntityTypeRegistry entityTypeRegistry,
            PiiResolutionPolicy resolutionPolicy,
            PiiAnalyzerFailureObserver failureObserver,
            PrivacyProcessingLimits processingLimits
    ) {
        this.processingLimits = Objects.requireNonNull(
                processingLimits, "processingLimits must not be null");
        this.analysisCoordinator = new PiiAnalysisCoordinator(
                analyzers,
                options,
                entityTypeRegistry,
                resolutionPolicy,
                failureObserver,
                this.processingLimits
        );
        this.contextRegistry = new PrivacyContextRegistry();
        this.sessionAnalysis = new SessionAnalysis(this.analysisCoordinator);
        this.textTransformer = new PrivacyTextTransformer(
                this.analysisCoordinator, this.sessionAnalysis, this.processingLimits);
        this.valueTreeTransformer = new PrivacyValueTreeTransformer(
                this.analysisCoordinator,
                this.sessionAnalysis,
                this.textTransformer,
                resolutionPolicy.typeConflictFallback(),
                this.processingLimits
        );
    }

    /**
     * Returns the processing limits used by this service.
     *
     * @return the immutable processing limits supplied at construction
     */
    public PrivacyProcessingLimits processingLimits() {
        return this.processingLimits;
    }

    /**
     * Returns final resolved spans.
     *
     * @param text source text. {@code null} or blank text produces an empty result
     * @return resolved spans in source order
     */
    public List<ResolvedPiiSpan> analyze(String text) {
        return this.analysisCoordinator.analyzeEvidence(text).result().spans();
    }

    /**
     * Analyzes each source text independently. Span offsets are relative to each text,
     * and results preserve input order. {@code null} or blank elements produce empty
     * span lists without invoking analyzers. The number of texts and their combined
     * length must fit the configured {@link PrivacyProcessingLimits#maxAnalysisSegments()}
     * and {@link PrivacyProcessingLimits#maxTextCharacters()} limits.
     *
     * @param texts independent source texts
     * @return immutable per-text resolved spans in input order
     */
    public List<List<ResolvedPiiSpan>> analyzeSegments(List<String> texts) {
        return this.analysisCoordinator.analyzeSegmentsEvidence(texts).stream()
                .map(item -> item.result().spans())
                .toList();
    }

    /**
     * Analyzes independent source texts in an active session. Successful analysis
     * for identical text may be reused. Results preserve input order.
     *
     * @param handle active session handle
     * @param texts independent source texts
     * @return immutable per-text resolved spans in input order
     */
    public List<List<ResolvedPiiSpan>> analyzeSegments(
            PrivacyContextHandle handle, List<String> texts) {
        return this.sessionAnalysis.analyzeSegmentsEvidence(
                texts, this.contextRegistry.requireActiveContext(handle)).stream()
                .map(item -> item.result().spans())
                .toList();
    }

    /**
     * Returns resolved spans together with successful providers and sanitized failures.
     *
     * @param text source text. {@code null} or blank text produces an empty result
     * @return detailed analysis result
     */
    public PiiAnalysisResult analyzeDetailed(String text) {
        return this.analysisCoordinator.analyzeEvidence(text).result();
    }

    /**
     * Opens an isolated privacy processing session.
     *
     * @return a session that must be closed after the request completes
     */
    public PrivacySession openSession() {
        return this.contextRegistry.openSession();
    }

    /**
     * Tokenizes text within an active session, reusing prior analysis or already
     * protected output when available.
     *
     * @param handle active session handle
     * @param text source text
     * @return tokenized text, or the unchanged {@code null} or blank input
     * @throws PrivacyGuardrailException if the session is not active
     */
    public String tokenize(PrivacyContextHandle handle, String text) {
        return this.textTransformer.tokenize(text, this.contextRegistry.requireActiveContext(handle));
    }

    /**
     * Analyzes and tokenizes source text within an active session. Successful
     * analysis for identical text may be reused.
     *
     * @param handle active session handle
     * @param text source text
     * @return the single analysis result and its tokenized text
     * @throws PrivacyGuardrailException if the session is not active
     */
    public PiiTokenizationResult analyzeAndTokenize(PrivacyContextHandle handle, String text) {
        return this.textTransformer.analyzeAndTokenize(
                text,
                this.contextRegistry.requireActiveContext(handle)
        );
    }

    /**
     * Resolves caller-supplied spans and tokenizes the protected text within a session.
     *
     * @param handle active session handle
     * @param text source text used by the supplied offsets
     * @param spans caller-supplied spans to validate and resolve
     * @return tokenized text
     * @throws PrivacyGuardrailException if the session is not active
     */
    public String tokenize(PrivacyContextHandle handle, String text, List<PiiSpan> spans) {
        return this.textTransformer.tokenize(
                text,
                spans,
                this.contextRegistry.requireActiveContext(handle)
        );
    }

    /**
     * Analyzes text and replaces protected spans with typed redaction markers.
     *
     * @param text source text
     * @return redacted text, or the unchanged {@code null} or blank input
     */
    public String redact(String text) {
        return this.textTransformer.redact(text);
    }

    /**
     * Resolves caller-supplied spans and replaces them with typed redaction markers.
     *
     * @param text source text used by the supplied offsets
     * @param spans caller-supplied spans to validate and resolve
     * @return redacted text
     */
    public String redact(String text, List<PiiSpan> spans) {
        return this.textTransformer.redact(text, spans);
    }

    /**
     * Redacts newly detected PII while preserving opaque tokens already owned by the session.
     *
     * @param handle active session handle
     * @param text source text
     * @return redacted text
     * @throws PrivacyGuardrailException if the session is not active
     */
    public String redact(PrivacyContextHandle handle, String text) {
        return this.textTransformer.redact(text, this.contextRegistry.requireActiveContext(handle));
    }

    /**
     * Redacts caller-analyzed spans while preserving opaque tokens already owned by the session.
     *
     * @param handle active session handle
     * @param text source text used by the supplied offsets
     * @param spans caller-supplied spans to validate and resolve
     * @return redacted text
     * @throws PrivacyGuardrailException if the session is not active
     */
    public String redact(PrivacyContextHandle handle, String text, List<PiiSpan> spans) {
        return this.textTransformer.redact(
                text,
                spans,
                this.contextRegistry.requireActiveContext(handle)
        );
    }

    /**
     * Returns whether text contains newly detected PII outside current-session opaque tokens.
     *
     * @param handle active session handle
     * @param text source text
     * @return {@code true} when newly detected PII remains
     * @throws PrivacyGuardrailException if the session is not active
     */
    public boolean containsPii(PrivacyContextHandle handle, String text) {
        return this.textTransformer.containsPii(
                text,
                this.contextRegistry.requireActiveContext(handle)
        );
    }

    /**
     * Checks caller-analyzed spans while excluding opaque tokens already owned by the session.
     *
     * @param handle active session handle
     * @param text source text used by the supplied offsets
     * @param spans caller-supplied spans to validate and resolve
     * @return {@code true} when a supplied protected span remains outside known tokens
     * @throws PrivacyGuardrailException if the session is not active
     */
    public boolean containsPii(PrivacyContextHandle handle, String text, List<PiiSpan> spans) {
        return this.textTransformer.containsPii(
                text,
                spans,
                this.contextRegistry.requireActiveContext(handle)
        );
    }

    /**
     * Restores every current-session opaque token found in text.
     *
     * @param handle active session handle
     * @param text text containing zero or more opaque tokens
     * @return text with known tokens restored
     * @throws PrivacyGuardrailException if the session is not active
     */
    public String detokenize(PrivacyContextHandle handle, String text) {
        return this.textTransformer.detokenize(
                text,
                this.contextRegistry.requireActiveContext(handle),
                null
        );
    }

    /**
     * Detokenizes only opaque tokens whose canonical entity type is explicitly allowed.
     *
     * @param handle active session handle
     * @param text text containing zero or more opaque tokens
     * @param allowedEntityTypes exact canonical entity types permitted for restoration
     * @return text with only permitted known tokens restored
     * @throws PrivacyGuardrailException if the session is not active
     */
    public String detokenize(
            PrivacyContextHandle handle,
            String text,
            Set<String> allowedEntityTypes
    ) {
        Set<String> canonicalTypes = this.valueTreeTransformer.requireValidEntityTypes(allowedEntityTypes);
        return this.textTransformer.detokenize(
                text,
                this.contextRegistry.requireActiveContext(handle),
                canonicalTypes
        );
    }

    /**
     * Restores all current-session tokens in a JSON-compatible value tree, including map keys.
     * Accepted values are {@code null}, booleans, strings, numbers of type
     * {@code Byte}, {@code Short}, {@code Integer}, {@code Long}, {@code BigInteger},
     * {@code BigDecimal}, {@code Float}, or {@code Double}, lists, and maps with
     * string keys. Floating-point values must be finite. Inputs are validated and
     * copied before transformation. Unsupported values, reference cycles, and values
     * above the configured processing limits are rejected.
     *
     * @param handle active session handle
     * @param valueTree JSON-compatible value tree
     * @return a transformed tree with known tokens restored to their original values and types
     * @throws PrivacyGuardrailException if the session is not active or the tree is invalid or oversized
     */
    public Object detokenizeValueTree(PrivacyContextHandle handle, Object valueTree) {
        return this.valueTreeTransformer.detokenizeValueTree(
                valueTree,
                this.contextRegistry.requireActiveContext(handle),
                null
        );
    }

    /**
     * Restores known tokens for the specified entity types in a value tree.
     * Accepts the same input types and applies the same validation, copying, and limits as
     * {@link #detokenizeValueTree(PrivacyContextHandle, Object)}.
     *
     * @param handle active session handle
     * @param valueTree JSON-compatible value tree
     * @param allowedEntityTypes exact canonical entity types permitted for restoration
     * @return a transformed tree with only permitted known tokens restored
     * @throws PrivacyGuardrailException if the session is not active or the tree is invalid or oversized
     */
    public Object detokenizeValueTree(
            PrivacyContextHandle handle,
            Object valueTree,
            Set<String> allowedEntityTypes
    ) {
        Set<String> canonicalTypes = this.valueTreeTransformer.requireValidEntityTypes(allowedEntityTypes);
        return this.valueTreeTransformer.detokenizeValueTree(
                valueTree,
                this.contextRegistry.requireActiveContext(handle),
                canonicalTypes
        );
    }

    /**
     * Tokenizes PII found in the string keys, string values, and numbers of a value tree.
     * {@link java.math.BigDecimal} values are analyzed using {@code toPlainString()};
     * other numbers use {@code toString()}.
     * A protected number becomes an opaque token and is restored to its original
     * numeric type by value-tree detokenization when its entity type is allowed.
     * Accepts the same input types and follows the same validation and limit rules as
     * {@link #detokenizeValueTree(PrivacyContextHandle, Object)}. Inputs are copied
     * before analysis.
     *
     * @param handle active session handle
     * @param valueTree JSON-compatible value tree
     * @return a transformed tree with detected values tokenized
     * @throws PrivacyGuardrailException if the session is not active or the tree is invalid or oversized
     */
    public Object tokenizeValueTree(PrivacyContextHandle handle, Object valueTree) {
        return this.valueTreeTransformer.tokenizeValueTree(
                valueTree,
                this.contextRegistry.requireActiveContext(handle)
        );
    }

    /**
     * Protects one pre-analyzed JSON-compatible string or numeric scalar.
     * Numeric span offsets refer to {@link java.math.BigDecimal#toPlainString()}
     * for decimal values and {@code toString()} for other numbers.
     * Numeric values retain their original type when no supplied span is protected.
     * Multiple distinct protected entity types in one numeric scalar use the configured
     * type-conflict fallback entity type. Numeric representations must fit the
     * configured value-tree character limit. Returned tokens must fit the output limit.
     *
     * @param handle active session handle
     * @param scalar JSON-compatible string or finite numeric scalar
     * @param spans caller-supplied spans to validate and resolve
     * @return the tokenized scalar, or the original numeric value when no span remains
     * @throws PrivacyGuardrailException if the session is not active or a processing limit is exceeded
     * @throws IllegalArgumentException if scalar is not supported
     */
    public Object tokenizeScalar(PrivacyContextHandle handle, Object scalar, List<PiiSpan> spans) {
        return this.valueTreeTransformer.tokenizeScalar(
                scalar,
                spans,
                this.contextRegistry.requireActiveContext(handle)
        );
    }

    /**
     * Analyzes and tokenizes JSON-compatible string and numeric scalars in an active
     * session. {@link java.math.BigDecimal} values use plain decimal text for analysis.
     * The configured processing limits apply to the batch.
     *
     * @param handle active session handle
     * @param scalars JSON-compatible string and numeric scalars in one logical batch
     * @return transformed scalars in input order
     */
    public List<Object> tokenizeScalars(PrivacyContextHandle handle, List<?> scalars) {
        return this.valueTreeTransformer.tokenizeScalars(
                scalars, this.contextRegistry.requireActiveContext(handle));
    }

    /**
     * Returns whether a handle currently identifies an active session.
     *
     * @param handle session handle to inspect
     * @return {@code true} when the session is active
     */
    public boolean isSessionActive(PrivacyContextHandle handle) {
        return this.contextRegistry.isActive(handle);
    }

    /**
     * Returns the number of active sessions owned by this service.
     *
     * @return active session count
     */
    public int activeSessionCount() {
        return this.contextRegistry.activeSessionCount();
    }
}
