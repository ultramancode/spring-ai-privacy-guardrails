package io.github.ultramancode.springai.privacy.sample.scenario;

import io.github.ultramancode.springai.privacy.core.PiiTokenizationResult;
import io.github.ultramancode.springai.privacy.core.ResolvedPiiSpan;
import io.github.ultramancode.springai.privacy.sample.dto.ProtectedPromptResponse;
import io.github.ultramancode.springai.privacy.sample.dto.ProtectedPromptResponse.DetectedSpan;
import io.github.ultramancode.springai.privacy.sample.dto.RagResponse;
import io.github.ultramancode.springai.privacy.sample.dto.SecurityBoundaryResponse;
import io.github.ultramancode.springai.privacy.sample.dto.SecurityBoundaryResponse.AuthorizationCheck;
import io.github.ultramancode.springai.privacy.sample.dto.SecurityBoundaryResponse.SecurityRoleEvidence;
import io.github.ultramancode.springai.privacy.sample.dto.ToolLoopResponse;
import io.github.ultramancode.springai.privacy.sample.dto.ToolLoopResponse.BoundaryEvidence;
import io.github.ultramancode.springai.privacy.sample.dto.ToolLoopResponse.EvidenceCount;

final class PrivacyDemoResponseMapper {

    private PrivacyDemoResponseMapper() {
    }

    static ProtectedPromptResponse toProtectedPromptResponse(PiiTokenizationResult tokenization) {
        return new ProtectedPromptResponse(
                tokenization.tokenizedText(),
                tokenization.analysis().spans().stream()
                        .map(PrivacyDemoResponseMapper::toDetectedSpan)
                        .toList(),
                tokenization.analysis().successfulProviders().stream().sorted().toList()
        );
    }

    static RagResponse toRagResponse(PrivacyDemoRag.Result result, int activeSessionsAfterCall) {
        return new RagResponse(
                result.retrievedDocument(),
                result.modelVisibleContext(),
                result.retrievedDocumentContainsRawPii(),
                result.modelVisibleContextContainsRawPii(),
                result.modelVisibleContextContainsTokenizedPii(),
                activeSessionsAfterCall
        );
    }

    static ToolLoopResponse toToolLoopResponse(
            String mode,
            PrivacyDemoToolLoop.Result result,
            int activeSessionsAfterCall
    ) {
        return new ToolLoopResponse(
                mode,
                result.modelCalls(),
                result.modelSawOnlyTokens(),
                result.protectedModelInput(),
                result.tokenizedToolArguments(),
                result.allowedOriginalEntityTypes(),
                result.toolReceivedOnlyAllowedOriginals(),
                result.toolLookupSucceededWithRestoredCustomerId(),
                result.toolResultRetokenizedBeforeModel(),
                toBoundaryEvidence(result.boundaryEvidence()),
                result.finalResponse(),
                activeSessionsAfterCall
        );
    }

    static SecurityBoundaryResponse toSecurityBoundaryResponse(
            String requestSummary,
            PrivacyDemoToolLoop.SecurityRun generalEmployee,
            PrivacyDemoToolLoop.SecurityRun customerSupport,
            int activeSessionsAfterCall
    ) {
        return new SecurityBoundaryResponse(
                "actual-spring-security-tool-boundary",
                requestSummary,
                toSecurityRoleEvidence(generalEmployee),
                toSecurityRoleEvidence(customerSupport),
                activeSessionsAfterCall
        );
    }

    private static DetectedSpan toDetectedSpan(ResolvedPiiSpan span) {
        return new DetectedSpan(
                span.entityType(),
                span.start(),
                span.end(),
                span.evidence().stream()
                        .map(evidence -> evidence.provider())
                        .distinct()
                        .sorted()
                        .toList(),
                span.reason()
        );
    }

    private static BoundaryEvidence toBoundaryEvidence(PrivacyDemoToolLoop.BoundaryEvidence evidence) {
        return new BoundaryEvidence(
                toEvidenceCount(evidence.modelRawValues()),
                toEvidenceCount(evidence.deniedToolRawValues()),
                toEvidenceCount(evidence.allowedToolRawValues()),
                toEvidenceCount(evidence.rawToolResultValuesAtModel())
        );
    }

    private static EvidenceCount toEvidenceCount(PrivacyDemoToolLoop.EvidenceCount evidence) {
        return new EvidenceCount(evidence.observed(), evidence.total(), evidence.passed());
    }

    private static SecurityRoleEvidence toSecurityRoleEvidence(PrivacyDemoToolLoop.SecurityRun run) {
        return new SecurityRoleEvidence(
                run.role(),
                run.exposedToolNames(),
                run.authorizationChecks().stream()
                        .map(PrivacyDemoResponseMapper::toAuthorizationCheck)
                        .toList(),
                run.modelRequestedTool(),
                run.toolCallDenied(),
                run.denialType(),
                run.callbackInvocations(),
                run.deniedCallStoppedBeforeCallback(),
                run.toolReceivedOnlyAllowedOriginals(),
                run.toolResultRetokenizedBeforeModel(),
                run.finalResponse()
        );
    }

    private static AuthorizationCheck toAuthorizationCheck(PrivacyDemoSecurityPolicy.AuthorizationCheck check) {
        return new AuthorizationCheck(check.toolName(), check.phase(), check.granted());
    }
}
