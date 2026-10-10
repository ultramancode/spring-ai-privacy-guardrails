package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;

/**
 * Receives inspection reports and failures for application logging, metrics or auditing.
 * Callbacks run on the calling thread, which may be a streaming worker, and should return promptly.
 * The same observer may be called concurrently. Runtime exceptions in callbacks do not change
 * the inspection decision. Fatal errors are not suppressed.
 */
@FunctionalInterface
public interface InspectionObserver {

    /** Receives ALLOW and BLOCK reports, including requests allowed by FAIL_OPEN after an inspector failed. */
    void onInspection(InspectionReport report);

    /**
     * Receives exceptions that stop text extraction, stream collection or inspection.
     * The exception includes a report when available. It does not retain inspected text
     * or the original exception cause.
     */
    default void onFailure(InspectionException failure) {}

    static InspectionObserver noop() {
        return report -> {};
    }
}
