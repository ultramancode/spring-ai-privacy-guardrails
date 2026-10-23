package io.github.ultramancode.springai.privacy.sample.shared.tool.dto.response;

import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolLoopResult;

import java.util.List;

public record ToolLoopDemoResponse(
        int modelCalls,
        String protectedModelInput,
        String tokenizedToolArguments,
        List<String> allowedOriginalEntityTypes,
        boolean toolReceivedOnlyAllowedOriginals,
        boolean toolLookupSucceeded,
        boolean toolResultRetokenizedBeforeModel,
        BoundaryEvidenceResponse boundaryEvidence,
        String finalResponse,
        int activeSessionsAfterCall
) {

    public static ToolLoopDemoResponse from(ToolLoopResult result, int activeSessionsAfterCall) {
        return new ToolLoopDemoResponse(
                result.modelCalls(),
                result.protectedModelInput(),
                result.tokenizedToolArguments(),
                result.allowedOriginalEntityTypes(),
                result.toolReceivedOnlyAllowedOriginals(),
                result.toolLookupSucceeded(),
                result.toolResultRetokenizedBeforeModel(),
                BoundaryEvidenceResponse.from(result.boundaryEvidence()),
                result.finalResponse(),
                activeSessionsAfterCall
        );
    }
}
