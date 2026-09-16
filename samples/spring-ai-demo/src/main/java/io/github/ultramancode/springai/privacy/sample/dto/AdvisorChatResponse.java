package io.github.ultramancode.springai.privacy.sample.dto;

public record AdvisorChatResponse(
        String modelResponse,
        int activeSessionsAfterCall
) {
}
