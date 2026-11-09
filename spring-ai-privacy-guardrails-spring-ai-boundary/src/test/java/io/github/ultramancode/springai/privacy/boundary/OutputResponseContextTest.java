package io.github.ultramancode.springai.privacy.boundary;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

class OutputResponseContextTest {

    @Test
    void capturesProcessedQuestionAndSimplifiesBeforeTheSessionEnds() {
        ChatClientRequest request = OutputResponseContext.requestPrivacyTracking(
                ChatClientRequest.builder().prompt(new Prompt("Alice asks")).build(), true);
        ChatClientRequest processed = request.mutate().prompt(new Prompt("[PII:opaque] asks")).build();
        processed = PiiTokenSimplifierContext.attach(processed, text -> text.replace("[PII:opaque]", "[PERSON]"));

        OutputResponseContext.captureUserMessage(processed, true);
        OutputResponseContext.captureUserMessage(processed, false);

        OutputResponseContext.UserMessageContext question = OutputResponseContext.findUserMessage(request);
        assertThat(question.text()).isEqualTo("[PII:opaque] asks");
        assertThat(question.privacyProcessed()).isTrue();
        assertThat(question.simplifiedText()).isEqualTo("[PERSON] asks");
        assertThat(request.context().toString()).doesNotContain("Alice", "opaque");
        assertThat(question.toString()).doesNotContain("opaque");
    }

    @Test
    void changedQuestionCannotReuseProcessingStatusOrSimplifiedText() {
        ChatClientRequest request = OutputResponseContext.requestPrivacyTracking(
                ChatClientRequest.builder().prompt(new Prompt("processed question")).build(), false);
        OutputResponseContext.captureUserMessage(request, true);

        OutputResponseContext.captureUserMessage(request.mutate().prompt(new Prompt("replacement question")).build(), false);

        OutputResponseContext.UserMessageContext question = OutputResponseContext.findUserMessage(request);
        assertThat(question.text()).isEqualTo("replacement question");
        assertThat(question.privacyProcessed()).isFalse();
        assertThat(question.simplifiedText()).isNull();
    }

    @Test
    void trackingIsIsolatedForEachInvocationOfTheSameRequest() {
        ChatClientRequest source = ChatClientRequest.builder().prompt(new Prompt("question")).build();
        ChatClientRequest first = OutputResponseContext.requestPrivacyTracking(source, false);
        ChatClientRequest second = OutputResponseContext.requestPrivacyTracking(source, false);

        OutputResponseContext.captureUserMessage(first.mutate().prompt(new Prompt("first question")).build(), true);

        assertThat(OutputResponseContext.findUserMessage(first).text()).isEqualTo("first question");
        assertThat(OutputResponseContext.findUserMessage(second).text()).isEqualTo("question");
        assertThat(OutputResponseContext.findUserMessage(second).privacyProcessed()).isFalse();
        assertThat(OutputResponseContext.findUserMessage(source)).isNull();
    }

    @Test
    void systemOnlyRequestsHaveNoReferenceQuestion() {
        ChatClientRequest request = OutputResponseContext.requestPrivacyTracking(
                ChatClientRequest.builder().prompt(new Prompt(new SystemMessage("instructions"))).build(), false);

        assertThat(OutputResponseContext.findUserMessage(request)).isNull();
    }
}
