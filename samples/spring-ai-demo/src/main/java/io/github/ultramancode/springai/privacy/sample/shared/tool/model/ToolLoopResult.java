package io.github.ultramancode.springai.privacy.sample.shared.tool.model;

import java.util.List;

public record ToolLoopResult(
        int modelCalls,
        String protectedModelInput,
        String tokenizedToolArguments,
        List<String> allowedOriginalEntityTypes,
        boolean toolReceivedOnlyAllowedOriginals,
        boolean toolLookupSucceeded,
        boolean toolResultRetokenizedBeforeModel,
        BoundaryEvidence boundaryEvidence,
        String finalResponse
) {

    public record BoundaryEvidence(
            EvidenceCount modelRawValues,
            EvidenceCount deniedToolRawValues,
            EvidenceCount allowedToolRawValues,
            EvidenceCount rawToolResultValuesAtModel
    ) {
    }

    public record EvidenceCount(int observed, int total, boolean passed) {

        public EvidenceCount {
            if (observed < 0 || total < 0 || observed > total) {
                throw new IllegalArgumentException("evidence counts are invalid");
            }
        }

        public static EvidenceCount expectedNone(int observed, int total) {
            return new EvidenceCount(observed, total, observed == 0);
        }

        public static EvidenceCount expectedAll(int observed, int total) {
            return new EvidenceCount(observed, total, observed == total);
        }
    }
}
