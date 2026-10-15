package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import io.github.ultramancode.springai.privacy.inspection.rules.InspectionRule;
import io.github.ultramancode.springai.privacy.inspection.rules.RuleBasedContentInspector;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Shared test fixtures for the inspection Spring AI module. */
final class InspectionTestSupport {
    private InspectionTestSupport() {
    }

    static InspectionService attackBlockingService() {
        InspectionRule rule = InspectionRule.literal("attack", InspectionFinding.Category.PROMPT_ATTACK, "attack");
        RuleBasedContentInspector inspector = new RuleBasedContentInspector("rules", List.of(rule));
        return new InspectionService(List.of(inspector));
    }

    /** Records model calls and prompts. Optionally requests lookup before returning done. */
    static final class RecordingChatModel implements ChatModel {
        final AtomicInteger modelCallCount = new AtomicInteger();
        final AtomicReference<Prompt> receivedPrompt = new AtomicReference<>();
        boolean lookupToolCallEnabled;

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            receivedPrompt.set(prompt);
            modelCallCount.incrementAndGet();
            if (lookupToolCallEnabled
                    && prompt.getInstructions().stream()
                            .noneMatch(ToolResponseMessage.class::isInstance)) {
                AssistantMessage.ToolCall toolCall =
                        new AssistantMessage.ToolCall("call-1", "function", "lookup", """
                                {}
                                """.strip());
                AssistantMessage assistantMessage = AssistantMessage.builder()
                        .content("")
                        .toolCalls(List.of(toolCall))
                        .build();
                return new ChatResponse(List.of(new Generation(assistantMessage)));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("done"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }
    }
}
