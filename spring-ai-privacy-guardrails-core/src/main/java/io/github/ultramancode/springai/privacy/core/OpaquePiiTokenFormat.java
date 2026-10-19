package io.github.ultramancode.springai.privacy.core;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.Pattern;

/** Provides syntax checks for opaque PII tokens. */
public final class OpaquePiiTokenFormat {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder NONCE_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int NONCE_BYTE_COUNT = 12;
    private static final int NONCE_CHARACTER_COUNT = 16;
    private static final String NONCE_REGEX = "[A-Za-z0-9_-]{" + NONCE_CHARACTER_COUNT + "}";
    private static final String INDEX_REGEX = "[1-9]\\d*";
    // Bound the entity type length before the index and colon.
    private static final Pattern CANONICAL_TOKEN_PATTERN = Pattern.compile(
            "\\[PII_(?=[A-Z0-9_]{1," + EntityTypeRegistry.MAX_ENTITY_TYPE_LENGTH
                    + "}_" + INDEX_REGEX + ":)"
                    + EntityTypeRegistry.ENTITY_TYPE_REGEX
                    + "_" + INDEX_REGEX + ":" + NONCE_REGEX + "]"
    );

    private OpaquePiiTokenFormat() {
    }

    /**
     * Returns a pattern that finds canonical opaque tokens for one entity type in arbitrary text.
     *
     * @param entityType entity type whose tokens should be matched
     * @return a compiled pattern restricted to canonical tokens of that entity type
     */
    public static Pattern patternForEntityType(String entityType) {
        String canonicalType = EntityTypeRegistry.requireValidEntityType(entityType);
        return Pattern.compile("\\[PII_" + Pattern.quote(canonicalType)
                + "_" + INDEX_REGEX + ":" + NONCE_REGEX + "]");
    }

    /**
     * Returns whether the complete value uses the canonical opaque-token grammar.
     *
     * @param value value to inspect
     * @return {@code true} only for one complete canonical opaque token
     */
    public static boolean isCanonicalToken(String value) {
        return value != null && CANONICAL_TOKEN_PATTERN.matcher(value).matches();
    }

    static Pattern canonicalTokenPattern() {
        return CANONICAL_TOKEN_PATTERN;
    }

    static int minimumGeneratedTokenLength(String entityType) {
        String canonicalType = EntityTypeRegistry.requireValidEntityType(entityType);
        // The shortest generated token has a one-digit index.
        return "[PII_".length()
                + canonicalType.length()
                + "_1:".length()
                + NONCE_CHARACTER_COUNT
                + "]".length();
    }

    static String format(String entityType, String nonce, int index) {
        if (index <= 0) {
            throw new IllegalArgumentException("index must be positive");
        }
        return "[PII_" + EntityTypeRegistry.requireValidEntityType(entityType)
                + "_" + index + ":" + nonce + "]";
    }

    static String randomNonce() {
        byte[] bytes = new byte[NONCE_BYTE_COUNT];
        RANDOM.nextBytes(bytes);
        return NONCE_ENCODER.encodeToString(bytes);
    }

}
