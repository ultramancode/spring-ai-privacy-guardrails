package io.github.ultramancode.springai.privacy.inspection.springai;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.docs.ObservationDocumentation;

/** Observation names and keys for inspector executions. Keys never contain inspected text or findings. */
public enum InspectorObservationDocumentation implements ObservationDocumentation {

    /** One inspector execution in model request or final output inspection. */
    INSPECTOR {
        @Override
        public Class<? extends ObservationConvention<? extends Observation.Context>> getDefaultConvention() {
            return DefaultInspectorObservationConvention.class;
        }

        @Override
        public KeyName[] getLowCardinalityKeyNames() {
            return LowCardinalityKeyNames.values();
        }
    };

    /** Low cardinality key names. Missing values are reported as {@code none}. */
    public enum LowCardinalityKeyNames implements KeyName {

        /** Configured inspector identifier. */
        INSPECTOR_ID {
            @Override
            public String asString() {
                return "spring.ai.inspector.id";
            }
        },

        /** Inspection phase, {@code MODEL_REQUEST} or {@code FINAL_OUTPUT}. */
        PHASE {
            @Override
            public String asString() {
                return "spring.ai.inspector.phase";
            }
        },

        /** Service decision after this inspector, {@code ALLOW} or {@code BLOCK}. */
        DECISION {
            @Override
            public String asString() {
                return "spring.ai.inspector.decision";
            }
        },

        /** Failure code of the validated inspector result. */
        FAILURE_CODE {
            @Override
            public String asString() {
                return "spring.ai.inspector.failure.code";
            }
        }
    }
}
