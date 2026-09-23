package io.github.ultramancode.springai.privacy.inspection.core;

/**
 * Thread-safe provider contract. Implementations must bound work, honor the shared
 * deadline/interruption, and never retain or log content. Arbitrary synchronous
 * application code cannot be forcibly cancelled by this interface.
 */
@FunctionalInterface
public interface ContentInspector {

    /**
     * Inspects the supplied segments within the request's limits and shared deadline.
     *
     * <p>A {@link InspectionResult.Status#COMPLETED} result must include every request segment ID
     * in {@link InspectionResult#completedSegmentIds()}, even when no findings were produced.
     * Failed results must preserve fully inspected segment IDs and findings collected before
     * the failure, including evidence from an incompletely inspected segment.
     *
     * @param request content snapshot and execution limits
     * @return completion status, inspected segment IDs and collected findings
     */
    InspectionResult inspect(InspectionRequest request);

    /** Stable, payload-free instance identifier, unique within a service; not a model or protocol ID. */
    default String inspectorId() {
        return "custom";
    }

    /** Requires the configured privacy transformation to have run; custom implementations default to true. */
    default boolean requiresPrivacyProcessedContent() {
        return true;
    }
}
