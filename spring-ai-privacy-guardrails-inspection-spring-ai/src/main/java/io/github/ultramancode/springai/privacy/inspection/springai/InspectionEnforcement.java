package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionDecision;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Shared ALLOW/BLOCK enforcement and payload-free observer notifications. */
final class InspectionEnforcement {
    private final InspectionService inspectionService;
    private final InspectionLimits limits;
    private final InspectionObserver observer;

    InspectionEnforcement(InspectionService inspectionService, InspectionLimits limits, InspectionObserver observer) {
        this.inspectionService = Objects.requireNonNull(inspectionService, "inspectionService");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    void inspect(Supplier<List<ContentSegment>> contentSegmentsSupplier,
            UnaryOperator<String> piiTokenSimplifier, InspectionExecution execution) {
        InspectionReport report;
        try {
            InspectionRequest.checkInterrupted();
            report = inspectionService.inspect(
                    new InspectionRequest(contentSegmentsSupplier.get(), limits), piiTokenSimplifier);
        } catch (RuntimeException ex) {
            throw failure(ex, execution);
        }
        try {
            observer.onInspection(report, execution);
        } catch (RuntimeException ignored) {
            /* Observability cannot change enforcement. */
        }
        if (Thread.currentThread().isInterrupted()) {
            InspectionReport cancelledReport = new InspectionReport(InspectionDecision.BLOCK, report.outcomes());
            throw failure(new InspectionException(InspectionFailureCode.CANCELLED, cancelledReport), execution);
        }
        if (report.decision() == InspectionDecision.BLOCK) {
            throw new InspectionBlockedException(report);
        }
    }

    InspectionException failure(RuntimeException ex, InspectionExecution execution) {
        InspectionFailureCode code = InspectionFailureCode.INVALID_RESULT;
        InspectionReport report = null;
        if (ex instanceof InspectionException classified) {
            code = classified.failureCode();
            report = classified.report().orElse(null);
        }
        if (Thread.currentThread().isInterrupted()) {
            code = InspectionFailureCode.CANCELLED;
        }
        InspectionException failure = new InspectionException(code, report);
        try {
            observer.onFailure(failure, execution);
        } catch (RuntimeException ignored) {
            /* Observability cannot change enforcement. */
        }
        return failure;
    }
}
