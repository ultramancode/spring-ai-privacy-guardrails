package io.github.ultramancode.springai.privacy.sample.config;

import io.github.ultramancode.springai.privacy.chatclient.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.chatclient.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.sample.shared.security.authorization.DemoToolAuthorization;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration(proxyBeanMethods = false)
class GuardrailsConfiguration {

    @Bean
    ToolDisclosurePolicy toolDisclosurePolicy() {
        return ToolDisclosurePolicy.allowEntityTypesByToolName(Map.of("customerLookup", List.of("CUSTOMER_ID")));
    }

    @Bean
    DemoToolAuthorization toolAuthorizationManager() {
        return new DemoToolAuthorization();
    }

    @Bean
    GuardrailsPolicy guardrailsPolicy(ToolDisclosurePolicy toolDisclosurePolicy) {
        GuardrailsPolicy.Privacy privacyPolicy = GuardrailsPolicy.Privacy.builder()
                .toolDisclosurePolicy(toolDisclosurePolicy)
                .outputProtection().tokenize()
                .build();
        return GuardrailsPolicy.builder()
                .privacy(privacyPolicy)
                .toolAuthorization(true)
                .build();
    }

    @Bean
    ChatClient guardedChatClient(ChatModel chatModel, GuardrailsChatClientFactory guardrailsChatClientFactory) {
        return guardrailsChatClientFactory.builder(chatModel).build();
    }
}
