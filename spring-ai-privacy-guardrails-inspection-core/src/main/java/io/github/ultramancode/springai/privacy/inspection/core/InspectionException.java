package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.Objects;

/** Sanitized failure: underlying prompts, responses and exception causes are not retained. */
public final class InspectionException extends RuntimeException {

    private final InspectionFailure failure;

    public InspectionException(InspectionFailure failure) {
        super("Content inspection failed: " + Objects.requireNonNull(failure, "failure").name());
        this.failure = failure;
    }

    public InspectionFailure failure() {
        return failure;
    }
}
