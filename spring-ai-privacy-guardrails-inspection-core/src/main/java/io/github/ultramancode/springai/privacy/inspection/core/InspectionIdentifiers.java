package io.github.ultramancode.springai.privacy.inspection.core;

/** Shared diagnostic identifier validation for content inspection. */
public final class InspectionIdentifiers {

    private InspectionIdentifiers() {
    }

    /**
     * Validates a 1-128 character diagnostic ID using ASCII letters, digits, underscores, dots and hyphens.
     * The first character must be a letter or digit.
     */
    public static String requireValid(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException("Expected a 1-128 character diagnostic identifier");
        }
        return value;
    }
}
