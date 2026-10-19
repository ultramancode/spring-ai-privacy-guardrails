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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Buffers streamed response frames and assembles their text for inspection. */
final class InspectionOutputBuffer {
    private final InspectionLimits limits;
    private final int maxFrames;
    private final List<ChatClientResponse> frames = new ArrayList<>();
    private final Map<String, OutputText> outputTextsByGroup = new LinkedHashMap<>();
    private Boolean usesExplicitChoiceIndexes;
    private Integer positionalChoiceCount;
    private int totalCharacters;

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
            Set<String> frameOutputGroupKeys = new HashSet<>();
            for (int position = 0; position < generations.size(); position++) {
                Generation generation = generations.get(position);
                AssistantMessage message = generation.getOutput();
                InspectionTextExtractor.requireSupportedAssistant(message);
                String outputGroupKey;
                if (OutputResponseContext.isCompleteToolResponse(generation)) {
                    outputGroupKey = "tool-response/" + frames.size() + "/" + position;
                } else {
                    outputGroupKey = choiceKey(generation, position, generations.size());
                }
                if (!frameOutputGroupKeys.add(outputGroupKey)) {
                    throw InspectionTextExtractor.unsupported();
                }
                String channel = OutputResponseContext.isThought(message) ? "/thought" : "/answer";
                append(outputGroupKey + channel, message.getText(), OutputResponseContext.isPrivacyProcessed(response));
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
        String choiceIndex = generationIndex != null ? generationIndex : messageIndex;
        boolean hasIndex = choiceIndex != null;
        if (usesExplicitChoiceIndexes != null && usesExplicitChoiceIndexes != hasIndex) {
            throw InspectionTextExtractor.unsupported();
        }
        usesExplicitChoiceIndexes = hasIndex;
        if (hasIndex) {
            return "index/" + choiceIndex;
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

    private void append(String key, String text, boolean privacyProcessed) {
        InspectionRequest.checkInterrupted();
        String value = text == null ? "" : text;
        if (value.length() > limits.maxCharacters() - totalCharacters) {
            throw limit();
        }
        // Only new keys add inspection segments.
        if (!outputTextsByGroup.containsKey(key) && outputTextsByGroup.size() >= limits.maxSegments()) {
            throw limit();
        }
        totalCharacters += value.length();
        OutputText output = outputTextsByGroup.computeIfAbsent(key, ignored -> new OutputText());
        output.text.append(value);
        // Keep the complete text from Privacy processing to compare with the accumulated text.
        // Later empty frames must not overwrite it.
        if (privacyProcessed && (output.privacyProcessedText == null || !value.isEmpty())) {
            output.privacyProcessedText = value;
        }
    }

    List<ContentSegment> buildSegments() {
        boolean allFramesProcessed = !frames.isEmpty()
                && frames.stream().allMatch(OutputResponseContext::isPrivacyProcessed);
        ContentSegment.PrivacyProcessingStatus emptyStatus = allFramesProcessed
                ? ContentSegment.PrivacyProcessingStatus.PROCESSED : ContentSegment.PrivacyProcessingStatus.UNKNOWN;
        InspectionTextExtractor extractor = new InspectionTextExtractor(limits, emptyStatus, "output-");
        // LinkedHashMap preserves the order in which output groups first appear.
        for (OutputText output : outputTextsByGroup.values()) {
            String text = output.text.toString();
            ContentSegment.PrivacyProcessingStatus privacyStatus = text.equals(output.privacyProcessedText)
                    ? ContentSegment.PrivacyProcessingStatus.PROCESSED : ContentSegment.PrivacyProcessingStatus.UNKNOWN;
            extractor.addOutputPayload(text, privacyStatus);
        }
        return extractor.buildSegments();
    }

    List<ChatClientResponse> framesWithoutPrivacyTracking() {
        return frames.stream().map(OutputResponseContext::clearPrivacyTracking).toList();
    }

    Map<String, String> collectSimplifiedTextsByOriginalText() {
        Map<String, String> simplifiedTextsByOriginalText = new HashMap<>();
        boolean simplificationPrepared = false;
        for (ChatClientResponse responseFrame : frames) {
            Map<String, String> frameSimplifiedTextsByOriginalText =
                    OutputResponseContext.findSimplifiedTextsByOriginalText(responseFrame);
            if (frameSimplifiedTextsByOriginalText != null) {
                simplifiedTextsByOriginalText.putAll(frameSimplifiedTextsByOriginalText);
                simplificationPrepared = true;
            }
        }
        if (!simplificationPrepared) {
            return null;
        }
        return simplifiedTextsByOriginalText;
    }

    private static InspectionException limit() {
        return new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
    }

    private static final class OutputText {
        private final StringBuilder text = new StringBuilder();
        private String privacyProcessedText;
    }
}
