package io.github.ultramancode.springai.privacy.sample.dto;

import java.util.List;

public record ToolLoopResponse(
        String mode,
        int modelCalls,
        boolean modelSawOnlyTokens,
        String protectedModelInput,
        String tokenizedToolArguments,
        List<String> allowedOriginalEntityTypes,
        boolean toolReceivedOnlyAllowedOriginals,
        boolean toolLookupSucceededWithRestoredCustomerId,
        boolean toolResultRetokenizedBeforeModel,
        BoundaryEvidence boundaryEvidence,
        String finalResponse,
        int activeSessionsAfterCall
) {

    public record BoundaryEvidence(
            EvidenceCount modelRawValues,
            EvidenceCount deniedToolRawValues,
            EvidenceCount allowedToolRawValues,
            EvidenceCount rawToolResultValuesAtModel
    ) {
    }

    public record EvidenceCount(int observed, int total, boolean passed) {
    }
}
