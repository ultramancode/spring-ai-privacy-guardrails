package io.github.ultramancode.springai.privacy.sample.inspection.infrastructure;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

public final class DemoInspectionChatModel implements ChatModel {

    private final String fixedResponse;
    private final InspectionEvidenceRecorder evidenceRecorder;

    public DemoInspectionChatModel(String fixedResponse, InspectionEvidenceRecorder evidenceRecorder) {
        this.fixedResponse = fixedResponse;
        this.evidenceRecorder = evidenceRecorder;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        this.evidenceRecorder.recordModelCall(prompt.getContents(), this.fixedResponse);
        return new ChatResponse(List.of(new Generation(new AssistantMessage(this.fixedResponse))));
    }
}
