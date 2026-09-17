package io.github.ultramancode.springai.privacy.inspection.core;

/** FAIL_OPEN applies only to operational failures, never cancellation or disclosure consent. */
public enum InspectionFailurePolicy {

    BLOCK,
    FAIL_OPEN
}
