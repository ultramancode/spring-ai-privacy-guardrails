package io.github.ultramancode.springai.privacy.sample.shared.tool.model;

public record ToolArgumentEvidence(
        int deniedRawValueCount,
        int allowedRawValueCount,
        boolean receivedOnlyAllowedOriginals
) {
}
