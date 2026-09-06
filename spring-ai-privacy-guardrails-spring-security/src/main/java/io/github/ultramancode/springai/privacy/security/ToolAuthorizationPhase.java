package io.github.ultramancode.springai.privacy.security;

/** Identifies when authorization is evaluated for a Spring AI tool. */
public enum ToolAuthorizationPhase {

    /** Authorization performed before exposing the tool definition to the model. */
    DEFINITION,

    /** Authorization performed before executing a tool requested by the model. */
    EXECUTION
}
