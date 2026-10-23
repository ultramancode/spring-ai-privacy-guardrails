package io.github.ultramancode.springai.privacy.sample.shared.tool.dto.response;

import io.github.ultramancode.springai.privacy.sample.shared.tool.model.ToolLoopResult.EvidenceCount;

public record EvidenceCountResponse(int observed, int total, boolean passed) {

    public static EvidenceCountResponse from(EvidenceCount evidence) {
        return new EvidenceCountResponse(evidence.observed(), evidence.total(), evidence.passed());
    }
}
