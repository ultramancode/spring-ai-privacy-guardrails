package io.github.ultramancode.springai.privacy.springai;

/** Privacy-safe outcome reported for a supported runtime boundary. */
public enum PrivacyEnforcementOutcome {

    /**
     * The boundary completed its protection step. This outcome does not indicate
     * whether the payload contained PII or required a content change.
     */
    PROTECTED,

    /** At least one original value explicitly allowed by tool policy was restored. */
    DISCLOSED,

    /** The application-facing output policy blocked the response. */
    BLOCKED
}
