package io.github.ultramancode.springai.privacy.sample.security.dto.response;

import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolSecurityResult;

import java.util.List;

public record RoleExecutionResponse(
        String role,
        List<String> exposedToolNames,
        List<ToolAuthorizationCheckResponse> authorizationChecks,
        boolean modelRequestedTool,
        boolean toolCallDenied,
        String denialExceptionType,
        int callbackInvocations,
        boolean deniedCallStoppedBeforeCallback,
        boolean toolReceivedOnlyAllowedOriginals,
        boolean toolResultRetokenizedBeforeModel,
        String finalResponse
) {

    public static RoleExecutionResponse from(ToolSecurityResult result) {
        List<ToolAuthorizationCheckResponse> authorizationChecks = result.authorizationChecks().stream()
                .map(ToolAuthorizationCheckResponse::from)
                .toList();
        return new RoleExecutionResponse(
                result.role(),
                result.exposedToolNames(),
                authorizationChecks,
                result.modelRequestedTool(),
                result.toolCallDenied(),
                result.denialExceptionType(),
                result.callbackInvocations(),
                result.deniedCallStoppedBeforeCallback(),
                result.toolReceivedOnlyAllowedOriginals(),
                result.toolResultRetokenizedBeforeModel(),
                result.finalResponse()
        );
    }
}
