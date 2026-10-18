package io.github.ultramancode.springai.privacy.inspection.springai;

import java.util.Objects;

/** Identifies where inspection ran, without retaining request or response content. */
public record InspectionExecution(Boundary boundary, boolean streaming) {
    public InspectionExecution {
        Objects.requireNonNull(boundary, "boundary");
    }

    public enum Boundary {
        MODEL_REQUEST,
        APPLICATION_OUTPUT
    }
}
