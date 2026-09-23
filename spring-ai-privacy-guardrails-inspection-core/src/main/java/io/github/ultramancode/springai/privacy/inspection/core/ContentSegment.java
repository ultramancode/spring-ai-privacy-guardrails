package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.Objects;

/**
 * One logical text unit in an inspection request, identified independently for findings and completion.
 * Segments retain request order and are not grouped by role. A segment need not correspond to an entire
 * chat message; providers may split it into internal windows without changing its identity.
 * IDs are diagnostic identifiers, never source text.
 */
public record ContentSegment(
        String id, Role role, PrivacyProcessingStatus privacyProcessingStatus, String text) {

    /** Message role, when known; does not establish the text's original source or trustworthiness. */
    public enum Role {
        USER,
        SYSTEM,
        ASSISTANT,
        TOOL,
        UNKNOWN
    }

    /** Privacy processing status supplied by trusted application integration for this segment's text. */
    public enum PrivacyProcessingStatus {
        /** Whether privacy processing completed for this text is unknown. */
        UNKNOWN,
        /** The caller knows that privacy processing has not been applied to this text. */
        UNPROCESSED,
        /** Configured privacy processing completed; this does not guarantee that all PII was detected. */
        PROCESSED
    }

    public ContentSegment {
        requireIdentifier(id);
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(privacyProcessingStatus, "privacyProcessingStatus");
        Objects.requireNonNull(text, "text");
    }

    public static String requireIdentifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Expected a 1-128 character diagnostic identifier");
        }
        return value;
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
