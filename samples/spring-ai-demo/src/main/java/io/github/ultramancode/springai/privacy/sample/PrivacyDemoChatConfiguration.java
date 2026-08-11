package io.github.ultramancode.springai.privacy.sample;

import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyChatClientConfigurer;
import io.github.ultramancode.springai.privacy.springai.PrivacyToolCallbackFactory;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
class PrivacyDemoChatConfiguration {

    @Bean("privacyDemoChatModel")
    ChatModel privacyDemoChatModel() {
        return new LocalEchoChatModel();
    }

    @Bean
    ChatClient privacyDemoChatClient(
            @Qualifier("privacyDemoChatModel") ChatModel chatModel,
            PrivacyChatClientConfigurer privacyConfigurer
    ) {
        return privacyConfigurer.configure(ChatClient.builder(chatModel)).build();
    }

    @Bean
    PrivacyDemoRag privacyDemoRag(PrivacyChatClientConfigurer privacyConfigurer) {
        return new PrivacyDemoRag(privacyConfigurer);
    }

    @Bean
    PrivacyDemoToolLoop privacyDemoToolLoop(
            PrivacyChatClientConfigurer privacyConfigurer,
            PrivacyToolCallbackFactory toolCallbackFactory,
            ToolDisclosurePolicy toolDisclosurePolicy,
            ObjectMapper objectMapper
    ) {
        return new PrivacyDemoToolLoop(
                privacyConfigurer,
                toolCallbackFactory,
                toolDisclosurePolicy,
                objectMapper
        );
    }

    @Bean(destroyMethod = "close")
    PrivacyDemoMcpToolLoop privacyDemoMcpToolLoop(
            PrivacyChatClientConfigurer privacyConfigurer,
            PrivacyToolCallbackFactory toolCallbackFactory,
            ToolDisclosurePolicy toolDisclosurePolicy,
            ObjectMapper objectMapper
    ) {
        return new PrivacyDemoMcpToolLoop(
                privacyConfigurer,
                toolCallbackFactory,
                toolDisclosurePolicy,
                objectMapper
        );
    }

    private static final class LocalEchoChatModel implements ChatModel {

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
}
