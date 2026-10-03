package io.github.ultramancode.springai.privacy.inspection.core;

/**
 * Chooses how to handle failures eligible for fail-open according to
 * {@link InspectionFailureCode#isFailOpenEligible()}.
 */
public enum InspectionFailurePolicy {

    /** Blocks when any inspector fails. */
    FAIL_CLOSED,
    /** Allows eligible failures only when the content policy also allows the retained findings. */
    FAIL_OPEN
}
