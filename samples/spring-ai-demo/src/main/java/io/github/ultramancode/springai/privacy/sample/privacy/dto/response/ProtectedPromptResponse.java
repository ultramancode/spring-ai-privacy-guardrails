package io.github.ultramancode.springai.privacy.sample.privacy.dto.response;

import io.github.ultramancode.springai.privacy.core.PiiResolutionReason;
import io.github.ultramancode.springai.privacy.core.PiiTokenizationResult;
import io.github.ultramancode.springai.privacy.core.ResolvedPiiSpan;

import java.util.List;

public record ProtectedPromptResponse(
        String protectedPrompt,
        List<DetectedSpan> detectedSpans,
        List<String> successfulProviders
) {

    public static ProtectedPromptResponse from(PiiTokenizationResult tokenization) {
        List<DetectedSpan> detectedSpans = tokenization.analysis().spans().stream()
                .map(DetectedSpan::from)
                .toList();
        List<String> successfulProviders = tokenization.analysis().successfulProviders().stream()
                .sorted()
                .toList();
        return new ProtectedPromptResponse(
                tokenization.tokenizedText(),
                detectedSpans,
                successfulProviders
        );
    }

    public record DetectedSpan(
            String type,
            int start,
            int end,
            List<String> providers,
            PiiResolutionReason reason
    ) {

        private static DetectedSpan from(ResolvedPiiSpan span) {
            List<String> providers = span.evidence().stream()
                    .map(evidence -> evidence.provider())
                    .distinct()
                    .sorted()
                    .toList();
            return new DetectedSpan(
                    span.entityType(),
                    span.start(),
                    span.end(),
                    providers,
                    span.reason()
            );
        }
    }
}
