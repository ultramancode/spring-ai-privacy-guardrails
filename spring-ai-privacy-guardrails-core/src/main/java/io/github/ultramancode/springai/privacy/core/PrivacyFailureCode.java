package io.github.ultramancode.springai.privacy.core;

/**
 * Stable, privacy-safe category for a failure owned by this library.
 *
 * <p>The code describes what prevented privacy processing from continuing; it
 * does not prescribe whether the application should retry. Model-provider and
 * application-owned delegate failures normally propagate unchanged and do not
 * receive one of these codes.
 */
public enum PrivacyFailureCode {

    /** Automatic analysis was requested without a configured {@link PiiAnalyzer}. */
    NO_ANALYZER_CONFIGURED,

    /** An analyzer failed without a more specific privacy-safe category. */
    ANALYZER_EXECUTION_FAILED,

    /** An analyzer configuration or result violated the {@link PiiAnalyzer} contract. */
    ANALYZER_CONTRACT_VIOLATION,

    /** An analyzer service could not be reached or was temporarily unavailable. */
    ANALYZER_UNAVAILABLE,

    /** An analyzer did not complete within its configured deadline. */
    ANALYZER_TIMEOUT,

    /** An analyzer service rejected its configured authentication or authorization. */
    ANALYZER_AUTHENTICATION_FAILED,

    /** An analyzer service rejected a request because its rate limit was reached. */
    ANALYZER_RATE_LIMITED,

    /** An analyzer response was malformed, out of bounds, or otherwise unsafe to consume. */
    ANALYZER_RESPONSE_INVALID,

    /** Every attempted analyzer failed and no successful analysis result was available. */
    ALL_ANALYZERS_FAILED,

    /** The current thread was interrupted while privacy analysis was running. */
    ANALYSIS_INTERRUPTED,

    /** A privacy boundary requiring request or session context received none. */
    CONTEXT_REQUIRED,

    /** A supplied privacy context was unknown, closed, or otherwise inactive. */
    CONTEXT_NOT_ACTIVE,

    /** A value or framework structure could not be transformed without ambiguity or data loss. */
    TRANSFORMATION_CONFLICT,

    /** A hard non-stream processing, result, or transformation size limit was exceeded. */
    PAYLOAD_LIMIT_EXCEEDED,

    /** A call or stream response exceeded its configured privacy-inspection limit. */
    RESPONSE_INSPECTION_LIMIT_EXCEEDED,

    /** A buffered response stream remained idle beyond its configured deadline. */
    STREAM_TIMEOUT,

    /** The configured output policy deliberately blocked privacy-sensitive content. */
    POLICY_BLOCKED,

    /** A privacy-wrapped tool was invoked without its required request-scoped tool context. */
    TOOL_CONTEXT_MISSING,

    /** The tool disclosure policy did not produce a usable disclosure decision. */
    TOOL_POLICY_FAILED,

    /** A tool invocation completed without returning the non-null result required by its contract. */
    TOOL_EXECUTION_FAILED,

    /** A tool callback provider returned no valid callback snapshot. */
    TOOL_PROVIDER_UNAVAILABLE,

    /** A tool callback did not expose a complete, readable definition. */
    TOOL_DEFINITION_UNAVAILABLE,

    /** A tool callback did not expose readable metadata. */
    TOOL_METADATA_UNAVAILABLE
}
