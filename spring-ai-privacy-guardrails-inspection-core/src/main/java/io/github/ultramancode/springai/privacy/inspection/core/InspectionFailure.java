package io.github.ultramancode.springai.privacy.inspection.core;

/** Payload-free inspection failure codes. */
public enum InspectionFailure {

    TIMEOUT,
    CANCELLED,
    LIMIT_EXCEEDED,
    DISCLOSURE_DENIED,
    INVALID_RESPONSE,
    TRANSPORT_ERROR,
    HTTP_ERROR,
    MODEL_ERROR,
    INCOMPLETE,
    INVALID_RESULT,
    CONFIGURATION,
    UNSUPPORTED_CONTENT
}
