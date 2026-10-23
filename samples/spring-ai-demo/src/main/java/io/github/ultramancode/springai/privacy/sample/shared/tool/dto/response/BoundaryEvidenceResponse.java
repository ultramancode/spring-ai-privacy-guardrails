package io.github.ultramancode.springai.privacy.sample.shared.tool.dto.response;

import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolLoopResult.BoundaryEvidence;

public record BoundaryEvidenceResponse(
        EvidenceCountResponse modelRawValues,
        EvidenceCountResponse deniedToolRawValues,
        EvidenceCountResponse allowedToolRawValues,
        EvidenceCountResponse rawToolResultValuesAtModel
) {

    public static BoundaryEvidenceResponse from(BoundaryEvidence evidence) {
        EvidenceCountResponse modelRawValues = EvidenceCountResponse.from(evidence.modelRawValues());
        EvidenceCountResponse deniedToolRawValues = EvidenceCountResponse.from(evidence.deniedToolRawValues());
        EvidenceCountResponse allowedToolRawValues = EvidenceCountResponse.from(evidence.allowedToolRawValues());
        EvidenceCountResponse rawToolResultValuesAtModel = EvidenceCountResponse.from(
                evidence.rawToolResultValuesAtModel());
        return new BoundaryEvidenceResponse(
                modelRawValues,
                deniedToolRawValues,
                allowedToolRawValues,
                rawToolResultValuesAtModel
        );
    }
}
