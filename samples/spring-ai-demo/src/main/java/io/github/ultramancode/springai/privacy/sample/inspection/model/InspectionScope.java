package io.github.ultramancode.springai.privacy.sample.inspection.model;

public enum InspectionScope {
    INPUT,
    OUTPUT,
    ALL;

    public boolean includesInput() {
        return this == INPUT || this == ALL;
    }

    public boolean includesOutput() {
        return this == OUTPUT || this == ALL;
    }
}
