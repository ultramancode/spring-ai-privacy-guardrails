package io.github.ultramancode.springai.privacy.inspection.core;

/**
 * Inspects request text and returns findings and completion status.
 * Implementations must be thread-safe, respect the request's timeout and respond to thread
 * interruption. Do not retain or log inspected text. The service cannot forcibly stop
 * an implementation that ignores timeout or interruption checks.
 */
public interface ContentInspector {

    /**
     * Inspects the supplied segments within the request's limits.
     * Call {@link InspectionRequest#checkActive()} before starting more work and use
     * {@link InspectionRequest#remaining()} to limit how long external calls may wait.
     *
     * <p>A {@link InspectionResult.Status#COMPLETED} result must include every request segment ID
     * in {@link InspectionResult#completedSegmentIds()}, even when no findings were produced.
     *
     * <p>On failure, return {@code InspectionResult.failed(code, completedIds, findings)}
     * with the IDs of fully inspected segments and all findings collected so far, including
     * findings from partly inspected segments. Throw an {@link InspectionException} only
     * when there are no results to preserve.
     * Other runtime exceptions are treated as {@link InspectionFailureCode#INVALID_RESULT}
     * and block the request even with {@link InspectionFailurePolicy#FAIL_OPEN}.</p>
     *
     * @param request content snapshot and execution limits
     * @return completion status, inspected segment IDs and collected findings
     */
    InspectionResult inspect(InspectionRequest request);

    /** Returns a stable ID, unique within the service. The ID must not contain inspected text. */
    String inspectorId();

    /**
     * Returns whether this inspector requires every segment to be marked
     * {@link ContentSegment.PrivacyProcessingStatus#PROCESSED}.
     * {@link InspectionService} checks this requirement before running any inspector.
     * If the input does not meet the requirement, the service stops the request regardless
     * of failure policy. This method declares a requirement and does not process the text.
     */
    boolean requiresPrivacyProcessedContent();
}
