package io.github.ultramancode.springai.privacy.boundary;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

class OutputResponseContextTest {

    @Test
    void privacyProcessedUserMessageReplacesTheInitialUserMessage() {
        ChatClientRequest request = OutputResponseContext.requestInspectionContext(
                ChatClientRequest.builder().prompt(new Prompt("Alice asks")).build(), true);
        OutputResponseContext.UserMessageContext initialUserMessage = OutputResponseContext.findUserMessage(request);
        assertThat(initialUserMessage.text()).isEqualTo("Alice asks");
        assertThat(initialUserMessage.privacyProcessed()).isFalse();
        assertThat(initialUserMessage.simplifiedText()).isNull();
        assertThat(request.context().toString()).doesNotContain("Alice");
        ChatClientRequest processed = request.mutate().prompt(new Prompt("[PII:opaque] asks")).build();
        processed = PiiTokenSimplifierContext.attach(processed, text -> text.replace("[PII:opaque]", "[PERSON]"));

        OutputResponseContext.capturePrivacyProcessedUserMessage(processed);

        OutputResponseContext.UserMessageContext userMessage = OutputResponseContext.findUserMessage(request);
        assertThat(userMessage.text()).isEqualTo("[PII:opaque] asks");
        assertThat(userMessage.privacyProcessed()).isTrue();
        assertThat(userMessage.simplifiedText()).isEqualTo("[PERSON] asks");
        assertThat(request.context().toString()).doesNotContain("opaque");
        assertThat(userMessage.toString()).doesNotContain("opaque");
    }

    @Test
    void inspectionContextIsIsolatedForEachInvocationOfTheSameRequest() {
        ChatClientRequest source = ChatClientRequest.builder().prompt(new Prompt("question")).build();
        ChatClientRequest first = OutputResponseContext.requestInspectionContext(source, false);
        ChatClientRequest second = OutputResponseContext.requestInspectionContext(source, false);

        OutputResponseContext.capturePrivacyProcessedUserMessage(
                first.mutate().prompt(new Prompt("first question")).build());

        assertThat(OutputResponseContext.findUserMessage(first).text()).isEqualTo("first question");
        assertThat(OutputResponseContext.findUserMessage(second).text()).isEqualTo("question");
        assertThat(OutputResponseContext.findUserMessage(second).privacyProcessed()).isFalse();
        assertThat(OutputResponseContext.findUserMessage(source)).isNull();
    }

    @Test
    void systemOnlyRequestsHaveNoUserMessage() {
        ChatClientRequest request = OutputResponseContext.requestInspectionContext(
                ChatClientRequest.builder().prompt(new Prompt(new SystemMessage("instructions"))).build(), false);

        assertThat(OutputResponseContext.findUserMessage(request)).isNull();
    }
}
