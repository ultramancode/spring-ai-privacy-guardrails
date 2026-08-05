package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.OpaquePiiTokenFormat;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PrivacyChatMemoryIntegrationTest {

    @Test
    void rawChatMemoryIsProtectedInTheModelCopyWithoutRewritingTheStore() {
        String conversationId = "privacy-memory-test";
        ChatMemory memory = MessageWindowChatMemory.builder().build();
        memory.add(conversationId, new UserMessage("Remember Alice"));
        PrivacyService service = TestPrivacyServices.privacyService();
        AtomicReference<Prompt> modelPrompt = new AtomicReference<>();
        ChatModel model = prompt -> {
            modelPrompt.set(prompt);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        };
        ChatClient client = ChatClient.builder(model)
                .defaultAdvisors(
                        new PrivacyLifecycleAdvisor(service),
                        MessageChatMemoryAdvisor.builder(memory).build(),
                        new PrivacyInputAdvisor(service),
                        new PrivacyToolContextAdvisor(service),
                        new PrivacyToolCallValidationAdvisor(service),
                        new PrivacyModelBoundaryAdvisor(service)
                )
                .build();

        String result = client.prompt()
                .user("What should I remember?")
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();

        String modelText = modelPrompt.get().getInstructions().stream()
                .map(Message::getText)
                .filter(Objects::nonNull)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        assertThat(result).isEqualTo("ok");
        assertThat(modelText)
                .containsPattern(OpaquePiiTokenFormat.patternForEntityType("PERSON"))
                .doesNotContain("Alice");
        assertThat(memory.get(conversationId).stream().map(Message::getText))
                .anyMatch(text -> text != null && text.contains("Alice"));
        assertThat(service.activeSessionCount()).isZero();
    }
}
