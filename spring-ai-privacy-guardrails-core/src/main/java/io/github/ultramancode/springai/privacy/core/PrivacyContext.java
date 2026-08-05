package io.github.ultramancode.springai.privacy.core;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Session-scoped bidirectional mapping between original PII and opaque tokens. */
final class PrivacyContext {

    private final Map<OriginalValue, String> originalToToken = new HashMap<>();
    private final Map<String, OriginalValue> originalValuesByToken = new LinkedHashMap<>();
    private final Map<String, Integer> lastTokenIndexByEntityType = new HashMap<>();
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
