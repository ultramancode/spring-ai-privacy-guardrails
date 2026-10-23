package io.github.ultramancode.springai.privacy.sample.rag.infrastructure;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

// One model instance records one demo request.
public final class DemoRagChatModel implements ChatModel {

    private String recordedPrompt;

    @Override
    public ChatResponse call(Prompt prompt) {
        String modelInput = prompt.getInstructions().stream()
                .map(Message::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        this.recordedPrompt = modelInput;
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage("ok")
        )));
    }

    public String requireRecordedPrompt() {
        String prompt = this.recordedPrompt;
        if (prompt == null) {
            throw new IllegalStateException("RAG demo model did not record a prompt");
        }
        return prompt;
    }
}
