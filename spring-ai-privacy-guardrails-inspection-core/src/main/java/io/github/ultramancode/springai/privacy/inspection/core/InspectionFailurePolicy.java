package io.github.ultramancode.springai.privacy.inspection.core;

/**
 * Controls operational failures such as timeouts and transport errors.
 * FAIL_OPEN never overrides input or work limits, cancellation, disclosure denial,
 * configuration errors, invalid inspector results or unsupported content.
 */
public enum InspectionFailurePolicy {

    FAIL_CLOSED,
    FAIL_OPEN
}
