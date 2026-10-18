package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;

/**
 * Receives inspection reports and failures for application logging, metrics or auditing.
 * Callbacks may run concurrently and should return promptly.
 * Runtime exceptions in callbacks do not change the inspection decision.
 */
@FunctionalInterface
public interface InspectionObserver {

    /**
     * Receives ALLOW and BLOCK reports, including requests allowed by FAIL_OPEN after an inspector failed.
     *
     * @param report completed inspector outcomes and the resulting decision
     * @param execution inspection boundary and call or stream mode
     */
    void onInspection(InspectionReport report, InspectionExecution execution);

    /**
     * Receives inspection failures reported as {@link InspectionException}.
     * The exception includes an inspection report when available.
     *
     * @param failure the classified inspection failure
     * @param execution inspection boundary and call or stream mode
     */
    default void onFailure(InspectionException failure, InspectionExecution execution) {}

    /**
     * Creates an observer that ignores all reports and failures.
     *
     * @return a no-op observer
     */
    static InspectionObserver noop() {
        return (report, execution) -> {};
    }
}
