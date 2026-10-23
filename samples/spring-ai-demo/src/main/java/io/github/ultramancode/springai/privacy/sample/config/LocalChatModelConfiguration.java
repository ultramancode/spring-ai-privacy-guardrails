package io.github.ultramancode.springai.privacy.sample.config;

import io.github.ultramancode.springai.privacy.sample.privacy.infrastructure.LocalEchoChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class LocalChatModelConfiguration {

    @Bean
    ChatModel demoChatModel() {
        return new LocalEchoChatModel();
    }
}
