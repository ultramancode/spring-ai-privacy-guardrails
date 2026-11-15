package io.github.ultramancode.springai.privacy.inspection.core;

/**
 * Receives the start and end of each inspector execution in {@link InspectionService}.
 * Notifications contain inspector identifiers, outcomes and decisions without inspected text.
 * Runtime exceptions thrown by the listener are ignored and do not change the inspection decision.
 */
@FunctionalInterface
public interface InspectorExecutionListener {

    /**
     * Called before the service executes an inspector. The service calls the returned
     * {@link Completion} once on the same thread when that inspector's step ends,
     * including when the service stops with an exception.
     *
     * @param inspectorId configured inspector identifier
     * @return receives the end of this inspector execution
     */
    Completion onStart(String inspectorId);

    /** Receives the end of one inspector execution. */
    @FunctionalInterface
    interface Completion {

        /**
         * Receives the validated outcome and the service decision after this inspector.
         * {@code BLOCK} means the service stops with this inspector, either because the content
         * policy blocked its findings or because its failure is handled by
         * {@link InspectionFailurePolicy#FAIL_CLOSED}. {@code ALLOW} means the request continues
         * to the next inspector or is allowed.
         *
         * @param outcome outcome recorded in the report, or {@code null} if an error prevented recording it
         * @param decision service decision after this inspector, or {@code null} if the service
         *        stopped with an exception before deciding
         */
        void onFinish(InspectionReport.Outcome outcome, InspectionDecision decision);
    }

    /**
     * Creates a listener that ignores all notifications.
     *
     * @return a no-op listener
     */
    static InspectorExecutionListener noop() {
        return inspectorId -> (outcome, decision) -> {};
    }
}
