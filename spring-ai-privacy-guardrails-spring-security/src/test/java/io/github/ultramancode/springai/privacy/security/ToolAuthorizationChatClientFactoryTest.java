package io.github.ultramancode.springai.privacy.security;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.Ordered;
import org.springframework.security.authorization.AuthorizationDecision;

import java.util.List;

import static io.github.ultramancode.springai.privacy.security.SecurityToolBoundaryTestFixtures.boundary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolAuthorizationChatClientFactoryTest {

    @Test
    void requiresAuthorizationBoundary() {
        assertThatThrownBy(() -> ToolAuthorizationChatClientFactory.builder().build())
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SpringSecurityToolBoundary");
    }

    @Test
    void reusingFactoryBuilderPreservesClientCustomizationAndAdvisorDefaults() {
        ToolCallingAdvisor.Builder<?> suppliedToolAdvisorBuilder = ToolCallingAdvisor.builder()
            .advisorOrder(100);
        ToolAuthorizationChatClientFactory.Builder factoryBuilder = ToolAuthorizationChatClientFactory.builder()
            .toolAuthorizationBoundary(boundary((authentication, context) -> new AuthorizationDecision(true)))
            .toolAdvisorBuilder(suppliedToolAdvisorBuilder)
            .clientCustomizer(chatClientBuilder -> chatClientBuilder.defaultSystem("original"));
        // This order is invalid for authorization and must not change the captured defaults.
        suppliedToolAdvisorBuilder.advisorOrder(Ordered.HIGHEST_PRECEDENCE);
        ToolAuthorizationChatClientFactory originalFactory = factoryBuilder.build();
        ToolAuthorizationChatClientFactory updatedFactory = factoryBuilder
            .toolAdvisorBuilder(ToolCallingAdvisor.builder().advisorOrder(200))
            .clientCustomizer(chatClientBuilder -> chatClientBuilder.defaultSystem("updated"))
            .build();
        ChatModel systemMessageModel = prompt -> new ChatResponse(List.of(
            new Generation(new AssistantMessage(prompt.getSystemMessage().getText()))));

        assertThat(originalFactory.builder(systemMessageModel)
            .build()
            .prompt()
            .user("hello")
            .call()
            .content()).isEqualTo("original");
        assertThat(updatedFactory.builder(systemMessageModel)
            .build()
            .prompt()
            .user("hello")
            .call()
            .content()).isEqualTo("updated");
        assertThat(originalFactory.defaultToolAdvisorBuilder().getAdvisorOrder()).isEqualTo(100);
        assertThat(updatedFactory.defaultToolAdvisorBuilder().getAdvisorOrder()).isEqualTo(200);
    }
}
