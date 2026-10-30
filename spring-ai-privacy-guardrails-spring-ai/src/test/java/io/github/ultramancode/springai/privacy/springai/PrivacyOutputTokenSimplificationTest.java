package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyProcessingLimits;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import io.github.ultramancode.springai.privacy.core.PrivacySession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.deepseek.DeepSeekAssistantMessage;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrivacyOutputTokenSimplificationTest {
    @ParameterizedTest
    @EnumSource(PrivacyOutputAction.class)
    void simplifiesBodyTokensAndPreservesOtherResponseFields(PrivacyOutputAction action) {
        PrivacyService service = service(PrivacyProcessingLimits.defaults());
        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), "Alice");
            DeepSeekAssistantMessage message = new DeepSeekAssistantMessage.Builder()
                    .reasoningContent(token).prefix(true)
                    .content(token)
                    .properties(Map.of("reasoningContent", token))
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup",
                            "{\"name\":\"" + token + "\"}")))
                    .build();
            ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                    .metadata("reasoningContent", token).build();
            PrivacyOutputAdvisor advisor = advisor(service, action);

            Generation result = advisor.protectAtApplicationBoundary(session.handle(),
                    new ChatClientResponse(new ChatResponse(List.of(new Generation(message, metadata))), Map.of()))
                    .chatResponse().getResult();

            assertThat(result.getOutput().getText()).isEqualTo("[PERSON_1]");
            assertThat(result.getOutput()).isInstanceOf(DeepSeekAssistantMessage.class);
            assertThat(((DeepSeekAssistantMessage) result.getOutput()).getReasoningContent()).isEqualTo(token);
            assertThat(result.getOutput().getMetadata()).containsEntry("reasoningContent", token);
            assertThat(result.getOutput().getToolCalls()).isEqualTo(message.getToolCalls());
            assertThat(result.getMetadata().<String>get("reasoningContent")).isEqualTo(token);
            if (action == PrivacyOutputAction.BLOCK) {
                assertThatThrownBy(() -> advisor.protectAtApplicationBoundary(session.handle(), response("Alice")))
                        .isInstanceOf(PrivacyOutputBlockedException.class);
            }
        }
    }

    @Test
    void simplifiesTokensAcrossStreamFramesAfterBuffering() {
        PrivacyService service = service(PrivacyProcessingLimits.defaults());
        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), "Alice");
            int splitIndex = token.length() / 2;
            List<ChatClientResponse> frames = advisor(service, PrivacyOutputAction.TOKENIZE)
                    .protectAtApplicationBoundary(session.handle(), Flux.just(
                            response(token.substring(0, splitIndex)),
                            response(token.substring(splitIndex) + " is available")))
                    .collectList().block(Duration.ofSeconds(5));

            assertThat(frames).hasSize(2);
            String body = frames.stream().map(frame -> frame.chatResponse().getResult().getOutput().getText())
                    .filter(Objects::nonNull).reduce("", String::concat);
            assertThat(body).isEqualTo("[PERSON_1] is available");
        }
    }

    @ParameterizedTest
    @CsvSource({"isThought, false", "isThought, true", "thinking, false", "thinking, true"})
    void protectsThoughtTextWithoutSimplifyingItsTokens(String marker, boolean streaming) {
        PrivacyService service = service(PrivacyProcessingLimits.defaults());
        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), "Alice");
            AssistantMessage thought = AssistantMessage.builder()
                    .content("Alice").properties(Map.of(marker, true)).build();
            ChatClientResponse thoughtResponse = new ChatClientResponse(
                    new ChatResponse(List.of(new Generation(thought))), Map.of());
            ChatClientResponse answerResponse = response("Alice");
            PrivacyOutputAdvisor advisor = advisor(service, PrivacyOutputAction.TOKENIZE);

            List<ChatClientResponse> protectedResponses;
            if (streaming) {
                protectedResponses = advisor.protectAtApplicationBoundary(session.handle(),
                        Flux.just(thoughtResponse, answerResponse)).collectList().block(Duration.ofSeconds(5));
            } else {
                protectedResponses = List.of(
                        advisor.protectAtApplicationBoundary(session.handle(), thoughtResponse),
                        advisor.protectAtApplicationBoundary(session.handle(), answerResponse));
            }

            assertThat(protectedResponses).hasSize(2);
            AssistantMessage protectedThought = protectedResponses.get(0).chatResponse().getResult().getOutput();
            assertThat(protectedThought.getText()).isEqualTo(token);
            assertThat(protectedThought.getMetadata()).containsEntry(marker, true);
            assertThat(protectedResponses.get(1).chatResponse().getResult().getOutput().getText())
                    .isEqualTo("[PERSON_1]");
        }
    }

    @Test
    void simplifiesTokensWithoutReapplyingJsonAnalysisLimits() {
        // 1e970 expands to 971 characters. Together with Alice it fits within 1000,
        // but replacing Alice with its opaque token exceeds that analysis limit.
        // Use a lower output limit to catch accidental reuse of that limit for numeric analysis.
        // The JSON still fits within 100 characters because it preserves scientific notation.
        PrivacyService service = service(PrivacyProcessingLimits.builder()
                .maxTextCharacters(1000).maxValueTreeCharacters(1000).maxOutputCharacters(100).build());
        try (PrivacySession session = service.openSession()) {
            String json = "{\"person\":\"Alice\",\"amount\":1e970}";

            String body = advisor(service, PrivacyOutputAction.TOKENIZE)
                    .protectAtApplicationBoundary(session.handle(), response(json))
                    .chatResponse().getResult().getOutput().getText();

            assertThat(body).isEqualTo("{\"person\":\"[PERSON_1]\",\"amount\":1e970}");
        }
    }

    @Test
    void allowsTokenExpansionBeyondInputLimits() {
        String json = "{\"x\":\"Alice\"}";
        PrivacyService service = service(PrivacyProcessingLimits.builder()
                .maxTextCharacters(json.length())
                .maxValueTreeCharacters("x".length() + "Alice".length())
                .build());
        try (PrivacySession session = service.openSession()) {
            PrivacyOutputAdvisor advisor = advisor(service, PrivacyOutputAction.TOKENIZE);
            String body = advisor.protectAtApplicationBoundary(session.handle(), response(json))
                    .chatResponse().getResult().getOutput().getText();
            assertThat(body).isEqualTo("{\"x\":\"[PERSON_1]\"}");
        }
    }

    @Test
    void simplifiesTokensWithoutReformattingJsonOutput() {
        PrivacyService service = service(PrivacyProcessingLimits.defaults());
        try (PrivacySession session = service.openSession()) {
            String token = service.tokenize(session.handle(), "Alice");
            String json = "{ \"person\": \"" + token + "\", \"values\": [1.00e+2, true, null] }";

            String body = advisor(service, PrivacyOutputAction.BLOCK)
                    .protectAtApplicationBoundary(session.handle(), response(json))
                    .chatResponse().getResult().getOutput().getText();

            assertThat(body).isEqualTo("{ \"person\": \"[PERSON_1]\", \"values\": [1.00e+2, true, null] }");
        }
    }

    private static PrivacyOutputAdvisor advisor(PrivacyService service, PrivacyOutputAction action) {
        return new PrivacyOutputAdvisor(service, action, "blocked", PrivacyResponseInspectionLimits.defaults(),
                PrivacyEnforcementObserver.noop(), PrivacyOutputAdvisor.DEFAULT_ORDER, true);
    }

    private static ChatClientResponse response(String text) {
        return new ChatClientResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))), Map.of());
    }

    private static PrivacyService service(PrivacyProcessingLimits limits) {
        return new PrivacyService(List.of((text, options, processingLimits) -> {
            int index = text.indexOf("Alice");
            return index < 0 ? List.of() : List.of(new PiiSpan("PERSON", index, index + "Alice".length(), 1.0));
        }), PiiAnalysisOptions.defaults(), limits);
    }
}
