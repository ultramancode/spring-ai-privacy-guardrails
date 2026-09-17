package io.github.ultramancode.springai.privacy.inspection.core;

import java.util.Objects;

/** One ordered text unit. IDs are diagnostic identifiers, never source text. */
public record ContentSegment(
        String id, Source source, Role role, Representation representation, String text) {

    public enum Source {
        USER,
        RETRIEVED,
        TOOL,
        UNKNOWN
    }

    public enum Role {
        USER,
        SYSTEM,
        ASSISTANT,
        TOOL,
        UNKNOWN
    }

    /** Identifies the text's state at the inspection boundary. */
    public enum Representation {
        RAW,
        AS_RECEIVED,
        PRIVACY_PROTECTED
    }

    public ContentSegment {
        requireIdentifier(id);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(representation, "representation");
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
                + ", source="
                + source
                + ", role="
                + role
                + ", representation="
                + representation
                + ", text=<redacted>]";
    }
}
