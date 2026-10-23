package io.github.ultramancode.springai.privacy.sample.privacy.service;

import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.sample.privacy.dto.response.ChatClientDemoResponse;
import io.github.ultramancode.springai.privacy.sample.shared.model.CustomerDemoData;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public final class ChatClientDemoService {

    private final ChatClient chatClient;
    private final PrivacyService privacyService;

    public ChatClientDemoService(ChatClient chatClient, PrivacyService privacyService) {
        this.chatClient = chatClient;
        this.privacyService = privacyService;
    }

    public ChatClientDemoResponse runChatClientDemo() {
        return chat(CustomerDemoData.DEFAULT.input());
    }

    public ChatClientDemoResponse chat(String input) {
        String finalResponse = this.chatClient.prompt().user(input).call().content();
        return new ChatClientDemoResponse(finalResponse, this.privacyService.activeSessionCount());
    }
}
