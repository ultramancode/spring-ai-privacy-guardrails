package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionDecision;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.micrometer.observation.Observation;

import java.util.Objects;

/**
 * Observation context for one inspector execution.
 * It contains identifiers and results, not inspected text or findings.
 * The decision and failure code are set when the execution ends.
 */
public final class InspectorObservationContext extends Observation.Context {
    private final String inspectorId;
    private final InspectionExecution inspectionExecution;
    private InspectionDecision decision;
    private InspectionFailureCode failureCode;

    /**
     * @param inspectorId configured inspector identifier
     * @param inspectionExecution inspection phase and call or stream mode
     */
    public InspectorObservationContext(String inspectorId, InspectionExecution inspectionExecution) {
        this.inspectorId = Objects.requireNonNull(inspectorId, "inspectorId");
        this.inspectionExecution = Objects.requireNonNull(inspectionExecution, "inspectionExecution");
    }

    public String getInspectorId() {
        return inspectorId;
    }

    public InspectionExecution getInspectionExecution() {
        return inspectionExecution;
    }

    /**
     * Returns the service decision after this inspector.
     *
     * @return the decision, or {@code null} before the execution ends or if the service stopped
     *         with an exception before deciding
     */
    public InspectionDecision getDecision() {
        return decision;
    }

    public void setDecision(InspectionDecision decision) {
        this.decision = decision;
    }

    /**
     * Returns the failure code of the validated inspector result.
     *
     * @return the failure code, or {@code null} if the inspector completed or the execution has not ended
     */
    public InspectionFailureCode getFailureCode() {
        return failureCode;
    }

    public void setFailureCode(InspectionFailureCode failureCode) {
        this.failureCode = failureCode;
    }
}
