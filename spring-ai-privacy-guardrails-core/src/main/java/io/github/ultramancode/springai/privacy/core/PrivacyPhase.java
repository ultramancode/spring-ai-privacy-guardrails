package io.github.ultramancode.springai.privacy.core;

/** Stable, low-cardinality phase in which privacy processing failed. */
public enum PrivacyPhase {

    /** Analyzer execution, evidence validation, or evidence resolution. */
    ANALYSIS,

    /** Replacement of detected original values with opaque tokens. */
    TOKENIZATION,

    /** Replacement of detected original values with typed redaction markers. */
    REDACTION,

    /** Restoration of authorized original values from opaque tokens. */
    DETOKENIZATION,

    /** Privacy-session creation, lookup, validation, or cleanup. */
    SESSION,

    /** Application-facing output-policy evaluation or blocking. */
    OUTPUT_POLICY,

    /** Validation and disclosure of a protected tool input. */
    TOOL_INPUT,

    /** Validation of the result contract at the delegate tool invocation boundary. */
    TOOL_EXECUTION,

    /** Protection of a delegate tool result. */
    TOOL_OUTPUT
}
