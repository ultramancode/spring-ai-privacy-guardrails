package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.springai.InspectorObservationDocumentation.LowCardinalityKeyNames;
import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

/** Default names and low cardinality keys for inspector execution observations. */
public class DefaultInspectorObservationConvention implements InspectorObservationConvention {

    public static final String DEFAULT_NAME = "spring.ai.inspector";

    private final String name;

    public DefaultInspectorObservationConvention() {
        this(DEFAULT_NAME);
    }

    public DefaultInspectorObservationConvention(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getContextualName(InspectorObservationContext context) {
        return "inspect " + context.getInspectorId();
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(InspectorObservationContext context) {
        return KeyValues.of(inspectorId(context), phase(context), decision(context), failureCode(context));
    }

    protected KeyValue inspectorId(InspectorObservationContext context) {
        return KeyValue.of(LowCardinalityKeyNames.INSPECTOR_ID, context.getInspectorId());
    }

    protected KeyValue phase(InspectorObservationContext context) {
        return KeyValue.of(LowCardinalityKeyNames.PHASE, context.getInspectionExecution().phase().name());
    }

    protected KeyValue decision(InspectorObservationContext context) {
        if (context.getDecision() == null) {
            return KeyValue.of(LowCardinalityKeyNames.DECISION, KeyValue.NONE_VALUE);
        }
        return KeyValue.of(LowCardinalityKeyNames.DECISION, context.getDecision().name());
    }

    protected KeyValue failureCode(InspectorObservationContext context) {
        if (context.getFailureCode() == null) {
            return KeyValue.of(LowCardinalityKeyNames.FAILURE_CODE, KeyValue.NONE_VALUE);
        }
        return KeyValue.of(LowCardinalityKeyNames.FAILURE_CODE, context.getFailureCode().name());
    }
}
