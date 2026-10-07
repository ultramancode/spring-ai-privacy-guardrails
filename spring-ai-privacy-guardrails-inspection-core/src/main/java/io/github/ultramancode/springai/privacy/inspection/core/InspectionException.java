package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Inspection failure without source text, model responses or an underlying exception cause.
 * {@link InspectionService} converts exceptions thrown by an inspector into failed results
 * and applies the failure policy. Exceptions propagated out of the service block the request.
 */
public final class InspectionException extends RuntimeException {

    private final InspectionFailureCode failureCode;
    private final InspectionReport report;

    public InspectionException(InspectionFailureCode failureCode) {
        this(failureCode, null);
    }

    /**
     * Creates a sanitized failure with outcomes collected before inspection stopped.
     * A null report means no aggregate report is attached.
     */
    public InspectionException(InspectionFailureCode failureCode, InspectionReport report) {
        super("Content inspection failed: " + Objects.requireNonNull(failureCode, "failureCode").name());
        this.failureCode = failureCode;
        if (report != null && report.decision() != InspectionDecision.BLOCK) {
            throw new IllegalArgumentException("A failed inspection report must block");
        }
        this.report = report;
    }

    public InspectionFailureCode failureCode() {
        return failureCode;
    }

    public Optional<InspectionReport> report() {
        return Optional.ofNullable(report);
    }
}
