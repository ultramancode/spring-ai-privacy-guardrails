package io.github.ultramancode.springai.privacy.inspection.core;

/**
 * Thread-safe provider contract. Implementations must bound work and waits using the shared
 * time budget, honor thread interruption, and never retain or log content. Arbitrary synchronous
 * application code cannot be forcibly cancelled by this interface.
 */
public interface ContentInspector {

    /**
     * Inspects the supplied segments within the request's limits.
     * Check {@link InspectionRequest#checkActive()} before further inspection work and
     * use {@link InspectionRequest#remaining()} to bound response waits.
     *
     * <p>A {@link InspectionResult.Status#COMPLETED} result must include every request segment ID
     * in {@link InspectionResult#completedSegmentIds()}, even when no findings were produced.
     * Failed results must preserve fully inspected segment IDs and findings collected before
     * the failure, including evidence from an incompletely inspected segment.
     *
     * <p>Report operational failures with a failed result or an {@link InspectionException}.
     * Unclassified runtime exceptions violate this contract and become
     * {@link InspectionFailureCode#INVALID_RESULT}, which cannot fail open.</p>
     *
     * @param request content snapshot and execution limits
     * @return completion status, inspected segment IDs and collected findings
     */
    InspectionResult inspect(InspectionRequest request);

    /** Returns an explicitly assigned, stable, payload-free instance identifier, unique within a service. */
    String inspectorId();

    /**
     * Whether this inspector accepts only segments marked
     * {@link ContentSegment.PrivacyProcessingStatus#PROCESSED}.
     * This is an input requirement and does not run privacy processing.
     */
    boolean requiresPrivacyProcessedContent();
}
