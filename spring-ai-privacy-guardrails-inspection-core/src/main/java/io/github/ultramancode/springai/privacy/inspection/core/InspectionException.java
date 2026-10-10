package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.Objects;
import java.util.Optional;

/**
 * Reports an inspection failure using a failure code and an optional report.
 * {@link InspectionService} handles exceptions from inspectors according to the failure policy.
 * An exception that leaves the service stops the request. The service does not include
 * inspected text, model responses or the original exception cause in the exception it throws.
 */
public final class InspectionException extends RuntimeException {

    private final InspectionFailureCode failureCode;
    private final InspectionReport report;

    public InspectionException(InspectionFailureCode failureCode) {
        this(failureCode, null);
    }

    /**
     * Creates a failure with the results collected before inspection stopped.
     * The report may be {@code null}. A supplied report must have a BLOCK decision.
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

    /**
     * Returns the inspection report, if available.
     * For exceptions thrown by {@link InspectionService}, {@link #failureCode()} describes
     * why the overall inspection stopped. The report preserves individual inspector results,
     * whose failure codes may differ from this exception's code.
     */
    public Optional<InspectionReport> report() {
        return Optional.ofNullable(report);
    }
}
