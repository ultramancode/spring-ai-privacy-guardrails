package io.github.ultramancode.springai.privacy.inspection.core;

/** Reasons why inspection failed, including whether FAIL_OPEN may allow the request to continue. */
public enum InspectionFailureCode {

    /** The shared time budget prevented further inspection work, or an individual operation timed out. */
    TIMEOUT(true),
    /** A model response was malformed or violated the selected output protocol. */
    INVALID_RESPONSE(true),
    /** Communication with an inspection backend failed. */
    TRANSPORT_ERROR(true),
    /** An HTTP endpoint returned a status not accepted by the inspector. */
    HTTP_ERROR(true),
    /** An inspector explicitly reported a model execution failure. */
    MODEL_ERROR(true),
    /** An inspector did not finish its work. Results may include completed segments and findings. */
    INCOMPLETE(true),

    /** Inspection was interrupted or cancelled. */
    CANCELLED(false),
    /** A configured count or size limit was exceeded. */
    LIMIT_EXCEEDED(false),
    /** An inspector requires privacy processing, but the input does not meet that requirement. */
    PRIVACY_PROCESSING_REQUIRED(false),
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
