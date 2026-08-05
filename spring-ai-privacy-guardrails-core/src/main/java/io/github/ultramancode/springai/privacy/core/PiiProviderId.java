package io.github.ultramancode.springai.privacy.core;

import java.util.Locale;
import java.util.regex.Pattern;

/** Owns the case-insensitive identifier contract shared by PII analyzer providers. */
final class PiiProviderId {

    private static final int MAX_LENGTH = 128;
    private static final Pattern SYNTAX = Pattern.compile(
            "[A-Za-z0-9]+(?:[-_][A-Za-z0-9]+)*"
    );

    private PiiProviderId() {
    }

    /**
     * Validates a provider ID and returns its uppercase canonical representation.
     * Only ASCII letter case is insignificant; whitespace and punctuation are not
     * repaired.
     */
    static String canonicalize(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        if (providerId.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("providerId is too long");
        }
        if (!SYNTAX.matcher(providerId).matches()) {
            throw new IllegalArgumentException(
                    "providerId must use ASCII letters, digits, hyphens, and underscores "
                            + "without leading, trailing, or repeated separators"
            );
        }
        return providerId.toUpperCase(Locale.ROOT);
    }
}
