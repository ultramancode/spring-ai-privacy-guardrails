package io.github.ultramancode.springai.privacy.inspection.springai;

import java.util.Objects;

/**
 * Identifies the inspection phase and execution mode.
 *
 * @param phase model request or final output inspection
 * @param streaming whether the client call is streaming
 */
public record InspectionExecution(Phase phase, boolean streaming) {
    public InspectionExecution {
        Objects.requireNonNull(phase, "phase");
    }

    public enum Phase {
        /** Before each model call, including calls after tool execution. */
        MODEL_REQUEST,
        /** Before ChatClient returns output to its caller. */
        FINAL_OUTPUT
    }
}
