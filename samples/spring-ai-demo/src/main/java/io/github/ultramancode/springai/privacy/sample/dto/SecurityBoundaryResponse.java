package io.github.ultramancode.springai.privacy.sample.dto;

import io.github.ultramancode.springai.privacy.security.ToolAuthorizationPhase;

import java.util.List;

public record SecurityBoundaryResponse(
        String mode,
        String requestSummary,
        SecurityRoleEvidence generalEmployee,
        SecurityRoleEvidence customerSupport,
        int activeSessionsAfterCall
) {

    public record SecurityRoleEvidence(
            String role,
            List<String> exposedToolNames,
            List<AuthorizationCheck> authorizationChecks,
            boolean modelRequestedTool,
            boolean toolCallDenied,
            String denialType,
            int callbackInvocations,
            boolean deniedCallStoppedBeforeCallback,
            boolean toolReceivedOnlyAllowedOriginals,
            boolean toolResultRetokenizedBeforeModel,
            String finalResponse
    ) {
    }

    public record AuthorizationCheck(
            String toolName,
            ToolAuthorizationPhase phase,
            boolean granted
    ) {
    }
}
