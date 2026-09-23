package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;

import java.util.Objects;

/**
 * Inspection prevented the pending business model call.
 *
 * <p>Earlier model calls in the same tool loop may already have completed.
 * Carries only payload-free inspection evidence.
 */
public final class InspectionBlockedException extends RuntimeException {

    private final InspectionReport report;

    public InspectionBlockedException(InspectionReport report) {
        super("Model-bound content was blocked by inspection");
        this.report = Objects.requireNonNull(report, "report");
    }

    public InspectionReport report() {
        return report;
    }
}
