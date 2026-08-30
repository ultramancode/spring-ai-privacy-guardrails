package io.github.ultramancode.springai.privacy.security;

/** Authorization checkpoints enforced around one Spring AI tool. */
public enum ToolAuthorizationPhase {

    /** Determines whether the tool definition may be disclosed to the model. */
    DEFINITION,

    /** Determines whether a model-requested tool may execute. */
    EXECUTION
}
