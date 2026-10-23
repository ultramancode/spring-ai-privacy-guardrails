package io.github.ultramancode.springai.privacy.sample.privacy.dto.response;

public record ChatClientDemoResponse(
        String finalResponse,
        int activeSessionsAfterCall
) {
}
