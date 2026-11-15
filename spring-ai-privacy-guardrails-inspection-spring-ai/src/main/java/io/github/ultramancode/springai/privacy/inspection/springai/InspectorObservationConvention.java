package io.github.ultramancode.springai.privacy.inspection.springai;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;

/** Convention for inspector execution observations. */
public interface InspectorObservationConvention extends ObservationConvention<InspectorObservationContext> {

    @Override
    default boolean supportsContext(Observation.Context context) {
        return context instanceof InspectorObservationContext;
    }
}
