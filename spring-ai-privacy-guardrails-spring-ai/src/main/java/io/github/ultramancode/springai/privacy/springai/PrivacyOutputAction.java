package io.github.ultramancode.springai.privacy.springai;

/** Actions available when privacy-sensitive model output is detected. */
public enum PrivacyOutputAction {

    /** Replaces detected values with request-scoped opaque tokens. */
    TOKENIZE,

    /** Replaces detected values with non-reversible redaction markers. */
    REDACT,

    /** Rejects the complete response when any sensitive value is detected. */
    BLOCK
}
