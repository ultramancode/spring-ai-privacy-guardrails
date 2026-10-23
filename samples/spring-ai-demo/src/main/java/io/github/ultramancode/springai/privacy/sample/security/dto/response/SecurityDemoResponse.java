package io.github.ultramancode.springai.privacy.sample.security.dto.response;

import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolSecurityResult;

public record SecurityDemoResponse(
        RoleExecutionResponse generalEmployee,
        RoleExecutionResponse customerSupport,
        int activeSessionsAfterCall
) {

    public static SecurityDemoResponse from(
            ToolSecurityResult generalEmployee,
            ToolSecurityResult customerSupport,
            int activeSessionsAfterCall
    ) {
        return new SecurityDemoResponse(
                RoleExecutionResponse.from(generalEmployee),
                RoleExecutionResponse.from(customerSupport),
                activeSessionsAfterCall
        );
    }
}
