package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.OutputResponseContext;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Per-subscription runtime text aggregation. Original frames are retained only for unchanged replay. */
final class InspectionOutputBuffer {
    private final InspectionLimits limits;
    private final int maxFrames;
    private final List<ChatClientResponse> frames = new ArrayList<>();
    private final Map<String, OutputText> payloads = new LinkedHashMap<>();
    private Boolean usesChoiceIndices;
    private Integer positionalChoiceCount;
    private int characters;

    InspectionOutputBuffer(InspectionLimits limits, int maxFrames) {
        this.limits = limits;
        this.maxFrames = maxFrames;
    }

    void accept(ChatClientResponse response) {
        InspectionRequest.checkInterrupted();
        if (frames.size() >= maxFrames) {
            throw limit();
        }
        if (response.chatResponse() != null) {
            List<Generation> generations = response.chatResponse().getResults();
            Set<String> frameChoiceKeys = new HashSet<>();
            for (int position = 0; position < generations.size(); position++) {
                Generation generation = generations.get(position);
                AssistantMessage message = generation.getOutput();
                InspectionTextExtractor.requireSupportedAssistant(message);
                String choiceKey;
                if (OutputResponseContext.isCompleteToolResponse(generation)) {
                    choiceKey = "tool-response/" + frames.size() + "/" + position;
                } else {
                    choiceKey = choiceKey(generation, position, generations.size());
                }
                if (!frameChoiceKeys.add(choiceKey)) {
                    throw InspectionTextExtractor.unsupported();
                }
                String channel = OutputResponseContext.isThought(message) ? "/thought" : "/answer";
                append(choiceKey + channel, message.getText(), OutputResponseContext.isPrivacyProcessed(response));
            }
        }
        frames.add(response);
    }

    private String choiceKey(Generation generation, int position, int choiceCount) {
        String generationIndex = index(generation.getMetadata().get("index"));
        String messageIndex = index(generation.getOutput().getMetadata().get("index"));
        if (generationIndex != null && messageIndex != null && !generationIndex.equals(messageIndex)) {
            throw InspectionTextExtractor.unsupported();
        }
        String explicitIndex = generationIndex != null ? generationIndex : messageIndex;
        boolean hasIndex = explicitIndex != null;
        if (usesChoiceIndices != null && usesChoiceIndices != hasIndex) {
            throw InspectionTextExtractor.unsupported();
        }
        usesChoiceIndices = hasIndex;
        if (hasIndex) {
            return "index/" + explicitIndex;
        }
        if (positionalChoiceCount != null && positionalChoiceCount != choiceCount) {
            throw InspectionTextExtractor.unsupported();
        }
        positionalChoiceCount = choiceCount;
        return "position/" + position;
    }

    private static String index(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return Long.toString(((Number) value).longValue());
        }
        throw InspectionTextExtractor.unsupported();
    }

    private void append(String key, String text, boolean processed) {
        InspectionRequest.checkInterrupted();
        String value = text == null ? "" : text;
        if (value.length() > limits.maxCharacters() - characters) {
            throw limit();
        }
        if (!payloads.containsKey(key) && payloads.size() >= limits.maxSegments()) {
            throw limit();
        }
        characters += value.length();
        OutputText output = payloads.computeIfAbsent(key, ignored -> new OutputText());
        output.text.append(value);
        // Privacy emits each complete protected choice/channel in its terminal frame.
        // Cleared or empty frames add no text. Concatenating other content invalidates completion.
        if (processed && (output.processedText == null || !value.isEmpty())) {
            output.processedText = value;
        }
    }

    List<ContentSegment> segments() {
        boolean allFramesProcessed = !frames.isEmpty()
                && frames.stream().allMatch(OutputResponseContext::isPrivacyProcessed);
        ContentSegment.PrivacyProcessingStatus emptyStatus = allFramesProcessed
                ? ContentSegment.PrivacyProcessingStatus.PROCESSED : ContentSegment.PrivacyProcessingStatus.UNKNOWN;
        InspectionTextExtractor extractor = new InspectionTextExtractor(limits, emptyStatus, "output-");
        for (OutputText output : payloads.values()) {
            String text = output.text.toString();
            ContentSegment.PrivacyProcessingStatus status = text.equals(output.processedText)
                    ? ContentSegment.PrivacyProcessingStatus.PROCESSED : ContentSegment.PrivacyProcessingStatus.UNKNOWN;
            extractor.outputPayload(text, status);
        }
        return extractor.segments();
    }

    List<ChatClientResponse> frames() {
        return frames.stream().map(OutputResponseContext::clearPrivacyTracking).toList();
    }

    private static InspectionException limit() {
        return new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
    }

    private static final class OutputText {
        private final StringBuilder text = new StringBuilder();
        private String processedText;
    }
}
