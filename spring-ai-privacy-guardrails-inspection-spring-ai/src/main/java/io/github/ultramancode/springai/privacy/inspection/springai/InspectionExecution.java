package io.github.ultramancode.springai.privacy.inspection.springai;

import java.util.Objects;

/** Identifies the inspection boundary and execution mode. */
public record InspectionExecution(Boundary boundary, boolean streaming) {
    public InspectionExecution {
        Objects.requireNonNull(boundary, "boundary");
    }

    public enum Boundary {
        MODEL_REQUEST,
        APPLICATION_OUTPUT
    }
}
