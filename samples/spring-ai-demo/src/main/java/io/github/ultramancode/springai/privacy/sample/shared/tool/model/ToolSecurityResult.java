package io.github.ultramancode.springai.privacy.sample.shared.tool.model;

import io.github.ultramancode.springai.privacy.sample.shared.security.model.ToolAuthorizationCheck;

import java.util.List;

public record ToolSecurityResult(
        String role,
        List<String> exposedToolNames,
        List<ToolAuthorizationCheck> authorizationChecks,
        boolean modelRequestedTool,
        String denialExceptionType,
        int callbackInvocations,
        ToolLoopResult completedResult
) {

    public boolean toolCallDenied() {
        return this.denialExceptionType != null;
    }

    public boolean deniedCallStoppedBeforeCallback() {
        return toolCallDenied() && this.callbackInvocations == 0;
    }

    public boolean toolReceivedOnlyAllowedOriginals() {
        if (this.completedResult == null) {
            return false;
        }
        return this.completedResult.toolReceivedOnlyAllowedOriginals();
    }

    public boolean toolResultRetokenizedBeforeModel() {
        if (this.completedResult == null) {
            return false;
        }
        return this.completedResult.toolResultRetokenizedBeforeModel();
    }

    public String finalResponse() {
        if (this.completedResult == null) {
            return null;
        }
        return this.completedResult.finalResponse();
    }
}
