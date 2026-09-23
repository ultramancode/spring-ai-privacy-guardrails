package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.Objects;
import java.util.Optional;

/** Sanitized failure: underlying prompts, responses and exception causes are not retained. */
public final class InspectionException extends RuntimeException {

    private final InspectionFailureCode failure;
    private final InspectionReport report;

    public InspectionException(InspectionFailureCode failure) {
        this(failure, null);
    }

    /**
     * Creates a sanitized failure with outcomes collected before inspection stopped.
     * A null report means failure occurred before an aggregate was available.
     */
    public InspectionException(InspectionFailureCode failure, InspectionReport report) {
        super("Content inspection failed: " + Objects.requireNonNull(failure, "failure").name());
        this.failure = failure;
        if (report != null && report.decision() != InspectionDecision.BLOCK) {
            throw new IllegalArgumentException("A failed inspection report must block");
        }
        this.report = report;
    }

    public InspectionFailureCode failure() {
        return failure;
    }

    public Optional<InspectionReport> report() {
        return Optional.ofNullable(report);
    }
}
