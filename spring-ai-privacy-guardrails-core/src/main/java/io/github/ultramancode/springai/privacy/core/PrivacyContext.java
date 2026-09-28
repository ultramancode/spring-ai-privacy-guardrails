package io.github.ultramancode.springai.privacy.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns token and analysis state for one privacy session. */
final class PrivacyContext {

    private static final int MAX_RETAINED_ENTRIES = 256;
    private static final int MAX_RETAINED_CHARACTERS = 131_072;
    private static final int MAX_RETAINED_EVIDENCE = 2_048;

    private final Map<OriginalValue, String> originalToToken = new HashMap<>();
    private final Map<String, OriginalValue> originalValuesByToken = new LinkedHashMap<>();
    private final Map<String, Integer> lastTokenIndexByEntityType = new HashMap<>();
    private final LinkedHashMap<String, PiiAnalysisCoordinator.AnalysisEvidence> analysisBySource =
            new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<String, Boolean> completedTokenizations =
            new LinkedHashMap<>(16, 0.75f, true);
    private int retainedCharacters;
    private int retainedEvidence;
    private int completedCharacters;
    private final String tokenNonce;
    private boolean closed;

    PrivacyContext() {
        this.tokenNonce = OpaquePiiTokenFormat.randomNonce();
    }

    synchronized String tokenFor(String entityType, String text) {
        return tokenFor(entityType, text, text);
    }

    synchronized String tokenForNumber(String entityType, Number value) {
        Number original = Objects.requireNonNull(value, "value must not be null");
        return tokenFor(entityType, original.toString(), original);
    }

    private String tokenFor(String entityType, String text, Object valueTreeValue) {
        ensureActive();
        String canonicalType = EntityTypeRegistry.requireValidEntityType(entityType);
        OriginalValue key = new OriginalValue(canonicalType, text, valueTreeValue);
        String existing = this.originalToToken.get(key);
        if (existing != null) {
            return existing;
        }

        int tokenIndex = this.lastTokenIndexByEntityType.merge(canonicalType, 1, Integer::sum);
        String token = OpaquePiiTokenFormat.format(canonicalType, this.tokenNonce, tokenIndex);
        this.originalToToken.put(key, token);
        this.originalValuesByToken.put(token, key);
        return token;
    }

    synchronized boolean hasTokens() {
        ensureActive();
        return !this.originalValuesByToken.isEmpty();
    }

    synchronized void requireActive() {
        ensureActive();
    }

    synchronized PiiAnalysisCoordinator.AnalysisEvidence analysisFor(String source) {
        ensureActive();
        return this.analysisBySource.get(source);
    }

    synchronized void retainAnalysis(String source, PiiAnalysisCoordinator.AnalysisEvidence evidence) {
        ensureActive();
        if (!evidence.result().failures().isEmpty()
                || source.length() > MAX_RETAINED_CHARACTERS
                || evidence.evidenceCount() > MAX_RETAINED_EVIDENCE) {
            return;
        }
        PiiAnalysisCoordinator.AnalysisEvidence old = this.analysisBySource.remove(source);
        if (old != null) {
            this.retainedCharacters -= source.length();
            this.retainedEvidence -= old.evidenceCount();
        }
        while (!this.analysisBySource.isEmpty()
                && (this.analysisBySource.size() >= MAX_RETAINED_ENTRIES
                || this.retainedCharacters + source.length() > MAX_RETAINED_CHARACTERS
                || this.retainedEvidence + evidence.evidenceCount() > MAX_RETAINED_EVIDENCE)) {
            Map.Entry<String, PiiAnalysisCoordinator.AnalysisEvidence> eldest =
                    this.analysisBySource.entrySet().iterator().next();
            this.retainedCharacters -= eldest.getKey().length();
            this.retainedEvidence -= eldest.getValue().evidenceCount();
            this.analysisBySource.remove(eldest.getKey());
        }
        this.analysisBySource.put(source, evidence);
        this.retainedCharacters += source.length();
        this.retainedEvidence += evidence.evidenceCount();
    }

    synchronized boolean isCompletedTokenization(String protectedText) {
        ensureActive();
        return this.completedTokenizations.get(protectedText) != null;
    }

    synchronized void retainCompletedTokenization(String protectedText) {
        ensureActive();
        if (protectedText.length() > MAX_RETAINED_CHARACTERS) {
            return;
        }
        if (this.completedTokenizations.remove(protectedText) != null) {
            this.completedCharacters -= protectedText.length();
        }
        while (!this.completedTokenizations.isEmpty()
                && (this.completedTokenizations.size() >= MAX_RETAINED_ENTRIES
                || this.completedCharacters + protectedText.length() > MAX_RETAINED_CHARACTERS)) {
            String eldest = this.completedTokenizations.keySet().iterator().next();
            this.completedTokenizations.remove(eldest);
            this.completedCharacters -= eldest.length();
        }
        this.completedTokenizations.put(protectedText, Boolean.TRUE);
        this.completedCharacters += protectedText.length();
    }

    synchronized boolean ownsToken(String token) {
        ensureActive();
        return this.originalValuesByToken.containsKey(token);
    }

    synchronized String originalTextForToken(String token, Set<String> allowedEntityTypes) {
        ensureActive();
        OriginalValue original = allowedOriginalValue(token, allowedEntityTypes);
        return original == null ? null : original.text();
    }

    synchronized Object originalValueTreeValueForToken(
            String token,
            Set<String> allowedEntityTypes
    ) {
        ensureActive();
        OriginalValue original = allowedOriginalValue(token, allowedEntityTypes);
        return original == null ? null : original.valueTreeValue();
    }

    synchronized void close() {
        this.closed = true;
        this.originalToToken.clear();
        this.originalValuesByToken.clear();
        this.lastTokenIndexByEntityType.clear();
        this.analysisBySource.clear();
        this.completedTokenizations.clear();
        this.retainedCharacters = 0;
        this.retainedEvidence = 0;
        this.completedCharacters = 0;
    }

    private void ensureActive() {
        if (this.closed) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.CONTEXT_NOT_ACTIVE,
                    PrivacyPhase.SESSION,
                    "Privacy context is already closed"
            );
        }
    }

    private OriginalValue allowedOriginalValue(String token, Set<String> allowedEntityTypes) {
        OriginalValue original = this.originalValuesByToken.get(token);
        if (original == null
                || allowedEntityTypes != null
                && !allowedEntityTypes.contains(original.entityType())) {
            return null;
        }
        return original;
    }

    private record OriginalValue(String entityType, String text, Object valueTreeValue) {

        private OriginalValue {
            Objects.requireNonNull(entityType, "entityType must not be null");
            Objects.requireNonNull(text, "text must not be null");
            Objects.requireNonNull(valueTreeValue, "valueTreeValue must not be null");
        }
    }
}
