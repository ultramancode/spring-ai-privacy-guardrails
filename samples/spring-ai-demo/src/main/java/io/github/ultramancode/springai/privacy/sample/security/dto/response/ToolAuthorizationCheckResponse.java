package io.github.ultramancode.springai.privacy.sample.security.dto.response;

import io.github.ultramancode.springai.privacy.sample.shared.security.model.ToolAuthorizationCheck;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationPhase;

public record ToolAuthorizationCheckResponse(
        String toolName,
        ToolAuthorizationPhase phase,
        boolean granted
) {

    public static ToolAuthorizationCheckResponse from(ToolAuthorizationCheck check) {
        return new ToolAuthorizationCheckResponse(check.toolName(), check.phase(), check.granted());
    }
}
