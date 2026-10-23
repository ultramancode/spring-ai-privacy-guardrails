package io.github.ultramancode.springai.privacy.sample.privacy.infrastructure;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class LocalEchoChatModel implements ChatModel {

    @Override
    public ChatResponse call(Prompt prompt) {
        String protectedPrompt = prompt.getInstructions().stream()
                .map(Message::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        return new ChatResponse(List.of(new Generation(
                new AssistantMessage("Local model received: " + protectedPrompt)
        )));
    }
}
