package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectorExecutionListener;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

/**
 * Reports each inspector execution as a Micrometer observation under the current observation.
 * Inspectors run with their observation in scope, so observations they create become its children.
 */
final class InspectorObservationListener implements InspectorExecutionListener {
    private static final InspectorObservationConvention DEFAULT_CONVENTION = new DefaultInspectorObservationConvention();

    private final ObservationRegistry observationRegistry;
    private final InspectorObservationConvention customConvention;
    private final InspectionExecution inspectionExecution;

    InspectorObservationListener(ObservationRegistry observationRegistry,
            InspectorObservationConvention customConvention, InspectionExecution inspectionExecution) {
        this.observationRegistry = observationRegistry;
        this.customConvention = customConvention;
        this.inspectionExecution = inspectionExecution;
    }

    @Override
    public Completion onStart(String inspectorId) {
        InspectorObservationContext context = new InspectorObservationContext(inspectorId, inspectionExecution);
        Observation observation = InspectorObservationDocumentation.INSPECTOR
                .observation(customConvention, DEFAULT_CONVENTION, () -> context, observationRegistry)
                .start();
        Observation.Scope scope = observation.openScope();
        return (outcome, decision) -> {
            scope.close();
            if (outcome != null) {
                InspectionFailureCode failureCode = outcome.result().failureCode();
                context.setDecision(decision);
                context.setFailureCode(failureCode);
                if (failureCode != null) {
                    observation.error(new InspectionException(failureCode));
                }
            }
            observation.stop();
        };
    }
}
