package io.github.ultramancode.springai.privacy.inspection.core;

/** Inspection failure reasons and their eligibility for explicit fail-open handling. */
public enum InspectionFailureCode {

    /** The shared inspection deadline or an individual operation timeout elapsed. */
    TIMEOUT(true),
    /** Inspection was interrupted or cancelled. */
    CANCELLED(false),
    /** A configured count or size limit was exceeded. */
    LIMIT_EXCEEDED(false),
    /** Content did not meet an inspector's privacy processing requirement. */
    DISCLOSURE_DENIED(false),
    /** A model response was malformed or violated the selected output protocol. */
    INVALID_RESPONSE(true),
    /** Communication with an inspection backend failed. */
    TRANSPORT_ERROR(true),
    /** An HTTP endpoint returned a status not accepted by the inspector. */
    HTTP_ERROR(true),
    /** An inspector explicitly reported a model execution failure. */
    MODEL_ERROR(true),
    /** An inspector explicitly reports incomplete work, retaining any valid partial evidence. */
    INCOMPLETE(true),
    /** An inspector violated its contract, including an unclassified runtime exception. */
    INVALID_RESULT(false),
    /** Inspection could not run with the supplied configuration. */
    CONFIGURATION(false),
    /** The request contained content outside the supported inspection scope. */
    UNSUPPORTED_CONTENT(false);

    private final boolean failOpenEligible;

    InspectionFailureCode(boolean failOpenEligible) {
        this.failOpenEligible = failOpenEligible;
    }

    /** Whether FAIL_OPEN may allow this failure when the content policy also allows it. */
    public boolean isFailOpenEligible() {
        return failOpenEligible;
    }
}
