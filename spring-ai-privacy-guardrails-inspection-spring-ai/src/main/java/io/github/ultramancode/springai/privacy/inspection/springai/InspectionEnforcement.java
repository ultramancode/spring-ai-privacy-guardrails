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

/** Shared ALLOW/BLOCK enforcement and payload-free observer notifications. */
final class InspectionEnforcement {
    private final InspectionService service;
    private final InspectionLimits limits;
    private final InspectionObserver observer;

    InspectionEnforcement(InspectionService service, InspectionLimits limits, InspectionObserver observer) {
        this.service = Objects.requireNonNull(service, "service");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    void inspect(Supplier<List<ContentSegment>> segments) {
        InspectionReport report;
        try {
            InspectionRequest.checkInterrupted();
            report = service.inspect(new InspectionRequest(segments.get(), limits));
        } catch (RuntimeException ex) {
            throw failure(ex);
        }
        try {
            observer.onInspection(report);
        } catch (RuntimeException ignored) {
            /* Observability cannot change enforcement. */
        }
        InspectionRequest.checkInterrupted();
        if (report.decision() == InspectionDecision.BLOCK) {
            throw new InspectionBlockedException(report);
        }
    }

    InspectionException failure(RuntimeException ex) {
        InspectionFailureCode code = InspectionFailureCode.INVALID_RESULT;
        InspectionReport report = null;
        if (ex instanceof InspectionException classified) {
            code = classified.failure();
            report = classified.report().orElse(null);
        }
        if (Thread.currentThread().isInterrupted()) {
            code = InspectionFailureCode.CANCELLED;
        }
        InspectionException failure = new InspectionException(code, report);
        try {
            observer.onFailure(failure);
        } catch (RuntimeException ignored) {
            /* Observability cannot change enforcement. */
        }
        return failure;
    }
}
