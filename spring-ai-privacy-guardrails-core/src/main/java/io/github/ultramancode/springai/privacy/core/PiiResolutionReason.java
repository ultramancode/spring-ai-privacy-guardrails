package io.github.ultramancode.springai.privacy.core;

/** Reason recorded for the final shape and type of a resolved PII span. */
public enum PiiResolutionReason {

    /** One evidence item alone produced the resolved span. */
    SINGLE_EVIDENCE,

    /** Multiple providers reported the same range and type. */
    EXACT_MATCH,

    /** One evidence range uniquely covered the complete overlap cluster. */
    COVERING_EVIDENCE,

    /** Partially overlapping evidence was merged to prevent substring leakage. */
    OVERLAP_UNION,

    /** Conflicting entity types required the policy's type-conflict fallback. */
    TYPE_CONFLICT
}
