package io.github.ultramancode.springai.privacy.sample.dto;

import io.github.ultramancode.springai.privacy.core.PiiResolutionReason;

import java.util.List;

public record ProtectedPromptResponse(
        String protectedPrompt,
        List<DetectedSpan> detectedSpans,
        List<String> successfulProviders
) {

    public record DetectedSpan(
            String type,
            int start,
            int end,
            List<String> providers,
            PiiResolutionReason reason
    ) {
    }
}
