package io.github.ultramancode.springai.privacy.sample.shared.security.model;

import io.github.ultramancode.springai.privacy.security.ToolAuthorizationPhase;

public record ToolAuthorizationCheck(
        String toolName,
        ToolAuthorizationPhase phase,
        boolean granted
) {
}
