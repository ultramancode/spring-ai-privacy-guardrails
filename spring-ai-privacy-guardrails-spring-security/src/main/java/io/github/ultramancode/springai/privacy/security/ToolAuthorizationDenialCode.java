package io.github.ultramancode.springai.privacy.security;

/**
 * Codes identifying why the tool authorization boundary rejects a request.
 */
public enum ToolAuthorizationDenialCode {

    /** A request with registered tools has no Authentication. */
    AUTHENTICATION_REQUIRED,

    /** The authorization policy denied tool execution. */
    POLICY_DENIED,

    /** The authorization policy returned no decision for tool execution. */
    NO_AUTHORIZATION_DECISION,

    /** The model requested a tool that was not exposed for the request. */
    TOOL_NOT_EXPOSED,

    /** A tool callback was added or replaced in an unsupported way during the request. */
    CALLBACK_CHANGED,

    /** The request's authorization session is missing, invalid, or closed. */
    INVALID_AUTHORIZATION_SESSION,

    /** The request is missing this client's managed tool advisor or uses a separate one. */
    UNMANAGED_TOOL_ADVISOR,

    /** Tool execution was requested without ToolCallingChatOptions. */
    INVALID_TOOL_CALL_OPTIONS
}
