package io.github.ultramancode.springai.privacy.inspection.core;

/**
 * Controls whether an inspection failure blocks the request.
 * Only failures identified by {@link InspectionFailureCode#isFailOpenEligible()}
 * can be allowed by this policy.
 */
public enum InspectionFailurePolicy {

    /** Blocks when the inspector fails. */
    FAIL_CLOSED,
    /** Continues after an eligible failure if the content policy allows the findings collected so far. */
    FAIL_OPEN
}
