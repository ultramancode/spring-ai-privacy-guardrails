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
    private final InspectionLimits inspectionLimits;
    private final InspectionObserver inspectionObserver;

    InspectionEnforcement(InspectionService inspectionService, InspectionLimits inspectionLimits,
        InspectionObserver inspectionObserver) {
        this.inspectionService = Objects.requireNonNull(inspectionService, "inspectionService");
        this.inspectionLimits = Objects.requireNonNull(inspectionLimits, "inspectionLimits");
        this.inspectionObserver = Objects.requireNonNull(inspectionObserver, "inspectionObserver");
    }

    void inspectAndEnforce(Supplier<List<ContentSegment>> contentSegmentsSupplier,
        UnaryOperator<String> piiTokenSimplifier, InspectionExecution inspectionExecution) {
        InspectionReport inspectionReport;
        try {
            InspectionRequest.checkInterrupted();
            inspectionReport = inspectionService.inspect(
                new InspectionRequest(contentSegmentsSupplier.get(), inspectionLimits), piiTokenSimplifier);
        } catch (RuntimeException ex) {
            throw failure(ex, inspectionExecution);
        }
        try {
            inspectionObserver.onInspection(inspectionReport, inspectionExecution);
        } catch (RuntimeException ignored) {
            /* Observability cannot change enforcement. */
        }
        if (Thread.currentThread().isInterrupted()) {
            InspectionReport cancelledReport = new InspectionReport(InspectionDecision.BLOCK, inspectionReport.outcomes());
            throw failure(new InspectionException(InspectionFailureCode.CANCELLED, cancelledReport), inspectionExecution);
        }
        if (inspectionReport.decision() == InspectionDecision.BLOCK) {
            throw new InspectionBlockedException(inspectionReport);
        }
    }

    InspectionException failure(RuntimeException ex, InspectionExecution inspectionExecution) {
        InspectionFailureCode failureCode = InspectionFailureCode.INVALID_RESULT;
        InspectionReport inspectionReport = null;
        if (ex instanceof InspectionException classified) {
            failureCode = classified.failureCode();
            inspectionReport = classified.report().orElse(null);
        }
        if (Thread.currentThread().isInterrupted()) {
            failureCode = InspectionFailureCode.CANCELLED;
        }
        InspectionException inspectionFailure = new InspectionException(failureCode, inspectionReport);
        try {
            inspectionObserver.onFailure(inspectionFailure, inspectionExecution);
        } catch (RuntimeException ignored) {
            /* Observability cannot change enforcement. */
        }
        return inspectionFailure;
    }
}
