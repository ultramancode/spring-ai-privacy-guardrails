package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;

/**
 * Receives payload-free inspection outcomes. Callbacks run inline, may run concurrently
 * or on a streaming worker, and should return promptly. Runtime exceptions from an
 * observer are isolated from enforcement. Fatal errors are not suppressed.
 */
@FunctionalInterface
public interface InspectionObserver {

    /** Receives allowed, blocked and explicitly allowed-after-failure reports. */
    void onInspection(InspectionReport report);

    /**
     * Receives hard failures from input extraction, request validation or inspection.
     * The exception carries collected outcomes when available, and retains no underlying cause.
     */
    default void onFailure(InspectionException failure) {}

    static InspectionObserver noop() {
        return report -> {};
    }
}
