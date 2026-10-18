package io.github.ultramancode.springai.privacy.sample.config;

import io.github.ultramancode.springai.privacy.client.GuardrailsChatClientFactory;
import io.github.ultramancode.springai.privacy.client.GuardrailsPolicy;
import io.github.ultramancode.springai.privacy.sample.scenario.PrivacyDemoMcpToolLoop;
import io.github.ultramancode.springai.privacy.sample.scenario.PrivacyDemoRag;
import io.github.ultramancode.springai.privacy.sample.scenario.PrivacyDemoSecurityPolicy;
import io.github.ultramancode.springai.privacy.sample.scenario.PrivacyDemoToolLoop;
import io.github.ultramancode.springai.privacy.security.ToolAuthorizationContext;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
class PrivacyDemoChatConfiguration {

    @Bean
    ToolDisclosurePolicy toolDisclosurePolicy() {
        return ToolDisclosurePolicy.byToolName(Map.of("customerLookup", List.of("CUSTOMER_ID")));
    }

    @Bean
    GuardrailsPolicy guardrailsPolicy(ToolDisclosurePolicy disclosurePolicy) {
        return GuardrailsPolicy.builder()
                .privacy(privacy -> privacy.toolDisclosurePolicy(disclosurePolicy).outputProtection(true))
                .toolAuthorization(true)
                .build();
    }

    @Bean("privacyDemoChatModel")
    ChatModel privacyDemoChatModel() {
        return new LocalEchoChatModel();
    }

    @Bean
    ChatClient privacyDemoChatClient(
            @Qualifier("privacyDemoChatModel") ChatModel chatModel,
            GuardrailsChatClientFactory guardrails
    ) {
        return guardrails.builder(chatModel).build();
    }

    @Bean
    PrivacyDemoRag privacyDemoRag(GuardrailsChatClientFactory guardrails) {
        return new PrivacyDemoRag(guardrails);
    }

    @Bean
    PrivacyDemoSecurityPolicy privacyDemoSecurityPolicy() {
        return new PrivacyDemoSecurityPolicy();
    }

    @Bean
    AuthorizationManager<ToolAuthorizationContext> toolAuthorizationManager(
            PrivacyDemoSecurityPolicy securityPolicy
    ) {
        return (authentication, context) -> securityPolicy.authorize(
                authentication.get(),
                context
        );
    }

    @Bean
    PrivacyDemoToolLoop privacyDemoToolLoop(
            GuardrailsChatClientFactory guardrails,
            ToolDisclosurePolicy toolDisclosurePolicy,
            ToolCallingManager toolCallingManager,
            PrivacyDemoSecurityPolicy securityPolicy,
            ObjectMapper objectMapper
    ) {
        return new PrivacyDemoToolLoop(
                guardrails,
                toolDisclosurePolicy,
                toolCallingManager,
                securityPolicy,
                objectMapper
        );
    }

    @Bean(destroyMethod = "close")
    PrivacyDemoMcpToolLoop privacyDemoMcpToolLoop(
            GuardrailsChatClientFactory guardrails,
            ToolDisclosurePolicy toolDisclosurePolicy,
            ToolCallingManager toolCallingManager,
            PrivacyDemoSecurityPolicy securityPolicy,
            ObjectMapper objectMapper
    ) {
        return new PrivacyDemoMcpToolLoop(
                guardrails,
                toolDisclosurePolicy,
                toolCallingManager,
                securityPolicy,
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
