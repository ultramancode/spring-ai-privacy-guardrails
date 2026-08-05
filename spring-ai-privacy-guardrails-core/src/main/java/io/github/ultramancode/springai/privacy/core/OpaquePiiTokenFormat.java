package io.github.ultramancode.springai.privacy.core;

import java.util.UUID;
import java.util.regex.Pattern;

/** Defines the canonical opaque token grammar shared by runtime and test-support modules. */
public final class OpaquePiiTokenFormat {

    private static final int NONCE_HEX_CHARACTER_COUNT = 32;
    private static final String NONCE_REGEX = "[a-f0-9]{" + NONCE_HEX_CHARACTER_COUNT + "}";
    private static final String INDEX_REGEX = "[1-9]\\d*";
    private static final Pattern NONCE_PATTERN = Pattern.compile(NONCE_REGEX);
    // The entity grammar has no length bound; this lookahead limits the type before the fixed-width nonce.
    private static final Pattern CANONICAL_TOKEN_PATTERN = Pattern.compile(
            "\\[\\[PII_(?=[A-Z0-9_]{1," + EntityTypeRegistry.MAX_ENTITY_TYPE_LENGTH
                    + "}_" + NONCE_REGEX + "_)"
                    + EntityTypeRegistry.ENTITY_TYPE_REGEX
                    + "_" + NONCE_REGEX + "_" + INDEX_REGEX + "]]"
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
        return Pattern.compile("\\[\\[PII_" + Pattern.quote(canonicalType)
                + "_" + NONCE_REGEX + "_" + INDEX_REGEX + "]]");
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

    static int maximumGeneratedTokenLength(String entityType) {
        String canonicalType = EntityTypeRegistry.requireValidEntityType(entityType);
        // Assume Integer.MAX_VALUE so output-limit checks include the longest generated index.
        return "[[PII_".length()
                + canonicalType.length()
                + 1
                + NONCE_HEX_CHARACTER_COUNT
                + 1
                + Integer.toString(Integer.MAX_VALUE).length()
                + "]]".length();
    }

    static String format(String entityType, String nonce, int index) {
        if (nonce == null || !NONCE_PATTERN.matcher(nonce).matches()) {
            throw new IllegalArgumentException(
                    "nonce must contain " + NONCE_HEX_CHARACTER_COUNT
                            + " lowercase hexadecimal characters"
            );
        }
        if (index <= 0) {
            throw new IllegalArgumentException("index must be positive");
        }
        return "[[PII_" + EntityTypeRegistry.requireValidEntityType(entityType)
                + "_" + nonce + "_" + index + "]]";
    }

    static String randomNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

}
