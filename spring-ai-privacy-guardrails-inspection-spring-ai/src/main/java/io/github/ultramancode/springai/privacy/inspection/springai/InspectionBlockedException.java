package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;

import java.util.Objects;

/**
 * Indicates that an inspection decision blocked a model request or final response.
 *
 * <p>Earlier model calls in the same tool loop may already have completed.
 * The attached report contains inspector results without inspected text.
 */
public final class InspectionBlockedException extends RuntimeException {

    private final InspectionReport report;

    /**
     * Creates a rejection containing the inspection report without retaining the inspected text.
     *
     * @param report the report that led to the rejection
     */
    public InspectionBlockedException(InspectionReport report) {
        super("Content was blocked by inspection");
        this.report = Objects.requireNonNull(report, "report");
    }

    /**
     * Returns the inspector outcomes and decision that led to the rejection.
     *
     * @return the inspection report
     */
    public InspectionReport report() {
        return report;
    }
}
