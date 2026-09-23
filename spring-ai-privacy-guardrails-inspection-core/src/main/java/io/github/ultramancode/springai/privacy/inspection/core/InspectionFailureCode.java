package io.github.ultramancode.springai.privacy.inspection.core;

/** Payload-free inspection failure codes. */
public enum InspectionFailureCode {

    TIMEOUT,
    CANCELLED,
    LIMIT_EXCEEDED,
    DISCLOSURE_DENIED,
    INVALID_RESPONSE,
    TRANSPORT_ERROR,
    HTTP_ERROR,
    MODEL_ERROR,
    /** An inspector explicitly reports incomplete work, retaining any valid partial evidence. */
    INCOMPLETE,
    /** The inspector violated the SPI contract; never allowed by FAIL_OPEN. */
    INVALID_RESULT,
    CONFIGURATION,
    UNSUPPORTED_CONTENT
}
