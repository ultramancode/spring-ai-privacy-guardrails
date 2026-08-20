package io.github.ultramancode.springai.privacy.springai;

/** Supported runtime boundary that completed a privacy-enforcement decision. */
public enum PrivacyEnforcementBoundary {

    /** Final request boundary immediately before model execution. */
    MODEL,

    /** Tool-argument boundary immediately before application tool execution. */
    TOOL_INPUT,

    /** Tool-result boundary before the result leaves the protected callback. */
    TOOL_RESULT,

    /** Final response boundary before content is returned to the application. */
    APPLICATION_OUTPUT
}
