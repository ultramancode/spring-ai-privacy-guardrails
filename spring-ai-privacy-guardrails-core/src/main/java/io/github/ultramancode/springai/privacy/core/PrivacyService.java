package io.github.ultramancode.springai.privacy.core;

import java.util.List;
import java.util.Set;

/** Public facade for PII analysis, transformation, and privacy session lifecycle. */
public final class PrivacyService {

    /** Hard maximum UTF-16 code units accepted for analysis or supplied-span resolution. */
    public static final int MAX_TEXT_INPUT_CHARACTERS = 1_000_000;

    /** Hard maximum for text produced by a privacy transformation that changes content. */
    public static final int MAX_TRANSFORMED_TEXT_CHARACTERS = 8_000_000;

    /** Hard maximum container nesting depth accepted by direct value-tree operations. */
    public static final int MAX_VALUE_TREE_DEPTH = 128;

    /** Hard maximum node count, including map keys, accepted by direct value-tree operations. */
    public static final int MAX_VALUE_TREE_NODES = 100_000;

    /** Hard maximum UTF-16 length of one string value or map key in a value tree. */
    public static final int MAX_VALUE_TREE_STRING_CHARACTERS = 250_000;

    /** Hard maximum character length of one numeric representation in a value tree. */
    public static final int MAX_VALUE_TREE_NUMBER_CHARACTERS = 1_000;

    /**
     * Hard maximum aggregate character count of strings, map keys, and numeric
     * representations accepted by one direct value-tree operation.
     */
    public static final int MAX_VALUE_TREE_INPUT_CHARACTERS = 1_000_000;

    private final PiiAnalysisCoordinator analysisCoordinator;
    private final PrivacyContextRegistry contextRegistry;
    private final PrivacyTextTransformer textTransformer;
    private final PrivacyValueTreeTransformer valueTreeTransformer;

    /**
     * Creates a service with the default entity registry and resolution policy.
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
                PiiAnalyzerFailureObserver.noop()
        );
    }

    /**
     * Creates a service with explicit entity and resolution policies.
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
        this(analyzers, options, entityTypeRegistry, resolutionPolicy, PiiAnalyzerFailureObserver.noop());
    }

    /**
     * Creates a service with explicit policies and a sanitized analyzer failure observer.
     *
     * @param analyzers analyzer instances shared across requests
     * @param options analysis language, included entity types, and score options
     * @param entityTypeRegistry canonical entity aliases and configured entity types
     * @param resolutionPolicy provider, failure, overlap, and conflict policy
     * @param failureObserver observer for sanitized analyzer failure events
     */
    public PrivacyService(
            List<PiiAnalyzer> analyzers,
            PiiAnalysisOptions options,
            EntityTypeRegistry entityTypeRegistry,
            PiiResolutionPolicy resolutionPolicy,
            PiiAnalyzerFailureObserver failureObserver
    ) {
        this.analysisCoordinator = new PiiAnalysisCoordinator(
                analyzers,
                options,
                entityTypeRegistry,
                resolutionPolicy,
                failureObserver
        );
        this.contextRegistry = new PrivacyContextRegistry();
        this.textTransformer = new PrivacyTextTransformer(this.analysisCoordinator);
        this.valueTreeTransformer = new PrivacyValueTreeTransformer(
                this.analysisCoordinator,
                this.textTransformer,
                resolutionPolicy.typeConflictFallback()
        );
    }

    /**
     * Returns final resolved spans.
     *
     * @param text source text; {@code null} or blank text produces an empty result
     * @return resolved spans in source order
     */
    public List<ResolvedPiiSpan> analyze(String text) {
        return this.analysisCoordinator.analyze(text);
    }

    /**
     * Returns resolved spans together with successful providers and sanitized failures.
     *
     * @param text source text; {@code null} or blank text produces an empty result
     * @return detailed analysis result
     */
    public PiiAnalysisResult analyzeDetailed(String text) {
        return this.analysisCoordinator.analyzeDetailed(text);
    }

    /**
     * Opens an isolated token-mapping session.
     *
     * @return a session that must be closed after the request completes
     */
    public PrivacySession openSession() {
        return this.contextRegistry.openSession();
    }

    /**
     * Analyzes and tokenizes text within an active session.
     *
     * @param handle active session handle
     * @param text source text
     * @return tokenized text, or the unchanged {@code null} or blank input
     * @throws PrivacyGuardrailException if the session is not active
     */
    public String tokenize(PrivacyContextHandle handle, String text) {
        return analyzeAndTokenize(handle, text).tokenizedText();
    }

    /**
     * Analyzes source text once and returns both its resolved spans and tokenized form.
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
     * Recursively restores all current-session tokens in a JSON-compatible value tree.
     * Accepted values are {@code null}, booleans, strings, numbers of type
     * {@code Byte}, {@code Short}, {@code Integer}, {@code Long}, {@code BigInteger},
     * {@code BigDecimal}, {@code Float}, or {@code Double}, lists, and maps with
     * string keys. Floating-point values must be finite. Inputs are validated and
     * copied before transformation; unsupported values, reference cycles, and values
     * above the published {@code MAX_VALUE_TREE_*} limits are rejected.
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
     * Recursively detokenizes only values belonging to explicitly allowed entity types.
     * The same accepted types, validation, copying, and limits as
     * {@link #detokenizeValueTree(PrivacyContextHandle, Object)} apply.
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
     * Recursively tokenizes strings and detected JSON-compatible numeric scalars.
     * A protected number becomes an opaque token and is restored to its original
     * numeric type only by recursive detokenization with sufficient disclosure scope.
     * Accepted values are {@code null}, booleans, strings, numbers of type
     * {@code Byte}, {@code Short}, {@code Integer}, {@code Long}, {@code BigInteger},
     * {@code BigDecimal}, {@code Float}, or {@code Double}, lists, and maps with
     * string keys. Floating-point values must be finite. Inputs are validated and
     * copied before analysis; unsupported values, reference cycles, and values above
     * the published {@code MAX_VALUE_TREE_*} limits are rejected.
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
     * Numeric values retain their original type when no supplied span is protected.
     * Multiple distinct protected entity types in one numeric scalar use the configured
     * type-conflict fallback entity type.
     *
     * @param handle active session handle
     * @param scalar JSON-compatible string or finite numeric scalar
     * @param spans caller-supplied spans to validate and resolve
     * @return the tokenized scalar, or the original numeric value when no span remains
     * @throws PrivacyGuardrailException if the session is not active
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
