package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;

import java.util.Objects;

/** The model was not called. Carries only payload-free inspection evidence. */
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
