package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.Objects;

/**
 * One logical text unit in an inspection request, identified independently for findings and completion.
 * A segment need not correspond to an entire chat message.
 */
public record ContentSegment(
        String id, Role role, PrivacyProcessingStatus privacyProcessingStatus, String text) {

    /** Message role, when known. */
    public enum Role {
        USER,
        SYSTEM,
        ASSISTANT,
        TOOL,
        UNKNOWN
    }

    /** Caller-reported privacy processing status for this segment's text. */
    public enum PrivacyProcessingStatus {
        /** Whether privacy processing completed for this text is unknown. */
        UNKNOWN,
        /** Privacy processing has not been applied to this text. */
        UNPROCESSED,
        /** Configured privacy processing completed. This does not guarantee that all PII was detected. */
        PROCESSED
    }

    public ContentSegment {
        InspectionIdentifiers.requireValid(id);
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(privacyProcessingStatus, "privacyProcessingStatus");
        Objects.requireNonNull(text, "text");
    }

    @Override
    public String toString() {
        return "ContentSegment[id="
                + id
                + ", role="
                + role
                + ", privacyProcessingStatus="
                + privacyProcessingStatus
                + ", text=<redacted>]";
    }
}
