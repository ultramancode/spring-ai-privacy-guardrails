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

    /**
     * Receives ALLOW and BLOCK reports, including requests allowed by FAIL_OPEN after an inspector failed.
     *
     * @param report completed inspector outcomes and the resulting decision
     */
    void onInspection(InspectionReport report);

    /**
     * Receives inspection failures from text extraction, stream limits or inspector execution.
     * The exception includes a report when available. It does not retain inspected text
     * or the original exception cause.
     * BLOCK decisions are delivered to {@link #onInspection(InspectionReport)}.
     * Model and tool errors propagate through Spring AI without inspection failure notifications.
     *
     * @param failure the classified inspection failure
     */
    default void onFailure(InspectionException failure) {}

    /**
     * Creates an observer that ignores all reports and failures.
     *
     * @return a no-op observer
     */
    static InspectionObserver noop() {
        return report -> {};
    }
}
