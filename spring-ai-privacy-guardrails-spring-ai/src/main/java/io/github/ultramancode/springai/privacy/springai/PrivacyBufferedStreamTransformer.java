package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Buffers a response stream, protects complete logical choices, and replays the
 * original response frames with their provider metadata intact.
 */
final class PrivacyBufferedStreamTransformer {

    private static final String CHOICE_INDEX_METADATA = "index";

    private PrivacyBufferedStreamTransformer() {
    }

    static List<ChatClientResponse> transform(
            List<ChatClientResponse> responses,
            BiFunction<AssistantMessage, ChatGenerationMetadata, AssistantMessage> messageTransformer,
            UnaryOperator<ChatGenerationMetadata> generationMetadataTransformer
    ) {
        List<ChatClientResponse> sourceResponses = List.copyOf(Objects.requireNonNull(
                responses,
                "responses must not be null"
        ));
        BiFunction<AssistantMessage, ChatGenerationMetadata, AssistantMessage> assistantMessageTransformer =
                Objects.requireNonNull(
                messageTransformer,
                "messageTransformer must not be null"
        );
        UnaryOperator<ChatGenerationMetadata> metadataTransformer = Objects.requireNonNull(
                generationMetadataTransformer,
                "generationMetadataTransformer must not be null"
        );
        if (sourceResponses.isEmpty()) {
            return List.of();
        }

        Map<ChoiceKey, List<GenerationOccurrence>> occurrencesByChoice = correlateChoices(sourceResponses);
        Map<Integer, Map<Integer, Generation>> replacements = new HashMap<>();
        for (List<GenerationOccurrence> occurrences : occurrencesByChoice.values()) {
            for (List<GenerationOccurrence> channelOccurrences : partitionContentChannels(occurrences).values()) {
                protectChoice(
                        channelOccurrences,
                        assistantMessageTransformer,
                        metadataTransformer,
                        replacements
                );
            }
        }

        List<ChatClientResponse> transformed = new ArrayList<>(sourceResponses.size());
        for (int responseIndex = 0; responseIndex < sourceResponses.size(); responseIndex++) {
            ChatClientResponse response = sourceResponses.get(responseIndex);
            Map<Integer, Generation> responseReplacements = replacements.get(responseIndex);
            if (responseReplacements == null || responseReplacements.isEmpty()) {
                transformed.add(response);
                continue;
            }

            ChatResponse original = response.chatResponse();
            if (original == null) {
                transformed.add(response);
                continue;
            }
            List<Generation> generations = new ArrayList<>(original.getResults());
            responseReplacements.forEach(generations::set);
            transformed.add(response.mutate()
                    .chatResponse(new ChatResponse(generations, original.getMetadata()))
                    .build());
        }
        return List.copyOf(transformed);
    }

    private static Map<ChoiceKey, List<GenerationOccurrence>> correlateChoices(List<ChatClientResponse> responses) {
        List<ResponseGenerations> frames = new ArrayList<>();
        Set<Integer> arities = new LinkedHashSet<>();
        boolean everyGenerationHasIndex = true;
        boolean anyGenerationHasIndex = false;
        boolean generationPresent = false;

        for (int responseIndex = 0; responseIndex < responses.size(); responseIndex++) {
            ChatResponse response = responses.get(responseIndex).chatResponse();
            if (response == null || response.getResults().isEmpty()) {
                continue;
            }
            List<Generation> generations = response.getResults();
            frames.add(new ResponseGenerations(responseIndex, generations));
            arities.add(generations.size());
            generationPresent = true;
            for (Generation generation : generations) {
                String index = choiceIndex(generation);
                everyGenerationHasIndex &= index != null;
                anyGenerationHasIndex |= index != null;
            }
        }
        if (!generationPresent) {
            return Map.of();
        }
        if ((!everyGenerationHasIndex && anyGenerationHasIndex)
                || (!everyGenerationHasIndex && arities.size() > 1)) {
            throw correlationFailure();
        }

        Map<ChoiceKey, List<GenerationOccurrence>> choices = new LinkedHashMap<>();
        for (ResponseGenerations frame : frames) {
            Set<ChoiceKey> frameKeys = new LinkedHashSet<>();
            for (int generationIndex = 0; generationIndex < frame.generations().size(); generationIndex++) {
                Generation generation = frame.generations().get(generationIndex);
                String explicitIndex = choiceIndex(generation);
                ChoiceKey key;
                if (everyGenerationHasIndex) {
                    key = new ChoiceKey("index:" + explicitIndex);
                } else {
                    key = new ChoiceKey("position:" + generationIndex);
                }
                if (!frameKeys.add(key)) {
                    throw correlationFailure();
                }
                choices.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(new GenerationOccurrence(frame.responseIndex(), generationIndex, generation));
            }
        }
        return choices;
    }

    private static void protectChoice(
            List<GenerationOccurrence> occurrences,
            BiFunction<AssistantMessage, ChatGenerationMetadata, AssistantMessage> transformer,
            UnaryOperator<ChatGenerationMetadata> metadataTransformer,
            Map<Integer, Map<Integer, Generation>> replacements
    ) {
        GenerationOccurrence metadataTerminal = occurrences.get(occurrences.size() - 1);
        ChatGenerationMetadata completeMetadata = PrivacyProviderTextMetadataTransformer.aggregateGenerationMetadata(
                occurrences.stream().map(occurrence -> occurrence.generation().getMetadata()).toList(),
                metadataTerminal.generation().getMetadata()
        );
        List<GenerationOccurrence> messageOccurrences = occurrences.stream()
                .filter(occurrence -> occurrence.generation().getOutput() != null)
                .toList();
        GenerationOccurrence messageTerminal = messageOccurrences.isEmpty()
                ? null
                : messageOccurrences.get(messageOccurrences.size() - 1);
        AssistantMessage protectedMessage = null;
        if (messageTerminal != null) {
            AssistantMessage terminalMessage = messageTerminal.generation().getOutput();
            AssistantMessage completeMessage = aggregateMessage(messageOccurrences, terminalMessage);
            protectedMessage = Objects.requireNonNull(
                    transformer.apply(completeMessage, completeMetadata),
                    "messageTransformer must not return null"
            );
        }
        ChatGenerationMetadata protectedMetadata = metadataTransformer.apply(completeMetadata);

        for (GenerationOccurrence occurrence : occurrences) {
            AssistantMessage original = occurrence.generation().getOutput();
            AssistantMessage replacementMessage = original == null
                    ? null
                    : occurrence == messageTerminal
                    ? protectedMessage
                    : clearStreamedSensitiveContent(original);
            ChatGenerationMetadata originalMetadata = occurrence.generation().getMetadata();
            ChatGenerationMetadata replacementMetadata = occurrence == metadataTerminal
                    ? protectedMetadata
                    : PrivacyProviderTextMetadataTransformer.clearGenerationTextMetadata(originalMetadata);
            if (replacementMessage == original && replacementMetadata == originalMetadata) {
                continue;
            }
            replacements.computeIfAbsent(occurrence.responseIndex(), ignored -> new HashMap<>())
                    .put(
                            occurrence.generationIndex(),
                            new Generation(replacementMessage, replacementMetadata)
                    );
        }
    }

    private static Map<ContentChannel, List<GenerationOccurrence>> partitionContentChannels(
            List<GenerationOccurrence> occurrences
    ) {
        Map<ContentChannel, List<GenerationOccurrence>> channels = new LinkedHashMap<>();
        for (GenerationOccurrence occurrence : occurrences) {
            AssistantMessage message = occurrence.generation().getOutput();
            ContentChannel channel = message == null
                    ? ContentChannel.DEFAULT
                    : contentChannel(message);
            channels.computeIfAbsent(channel, ignored -> new ArrayList<>()).add(occurrence);
        }
        return channels;
    }

    private static ContentChannel contentChannel(AssistantMessage message) {
        PrivacyAssistantMessageSupport.requireSupported(message, PrivacyPhase.OUTPUT_POLICY);
        Map<String, Object> metadata = message.getMetadata();
        return Boolean.TRUE.equals(metadata.get("isThought"))
                || Boolean.TRUE.equals(metadata.get("thinking"))
                ? ContentChannel.THOUGHT
                : ContentChannel.DEFAULT;
    }

    private static AssistantMessage aggregateMessage(
            List<GenerationOccurrence> occurrences,
            AssistantMessage terminalMessage
    ) {
        StringBuilder content = new StringBuilder();
        boolean contentPresent = false;
        List<AssistantMessage> messages = new ArrayList<>(occurrences.size());
        String providerSpecificText = null;
        boolean providerSpecificTextPresent = false;
        for (GenerationOccurrence occurrence : occurrences) {
            AssistantMessage message = occurrence.generation().getOutput();
            PrivacyAssistantMessageSupport.requireSupported(message, PrivacyPhase.OUTPUT_POLICY);
            if (!PrivacyAssistantMessageSupport.haveSameRuntimeType(terminalMessage, message)) {
                throw correlationFailure();
            }
            messages.add(message);
            if (message.getText() != null) {
                content.append(message.getText());
                contentPresent = true;
            }
            String providerSpecificFragment = PrivacyAssistantMessageSupport.providerSpecificText(message);
            if (providerSpecificFragment != null) {
                providerSpecificText = mergeFragment(providerSpecificText, providerSpecificFragment);
                providerSpecificTextPresent = true;
            }
        }
        return PrivacyAssistantMessageSupport.rebuild(
                terminalMessage,
                contentPresent ? content.toString() : null,
                PrivacyProviderTextMetadataTransformer.aggregateMessageMetadata(
                        messages.stream().map(AssistantMessage::getMetadata).toList(),
                        terminalMessage.getMetadata()
                ),
                aggregateToolCalls(messages),
                terminalMessage.getMedia(),
                providerSpecificTextPresent ? providerSpecificText : null
        );
    }

    private static List<AssistantMessage.ToolCall> aggregateToolCalls(List<AssistantMessage> messages) {
        List<List<AssistantMessage.ToolCall>> chunks = messages.stream()
                .map(AssistantMessage::getToolCalls)
                .filter(toolCalls -> !toolCalls.isEmpty())
                .toList();
        if (chunks.isEmpty()) {
            return List.of();
        }
        if (chunks.size() == 1) {
            return List.copyOf(chunks.get(0));
        }

        boolean everyCallHasId = chunks.stream()
                .flatMap(List::stream)
                .allMatch(toolCall -> hasText(toolCall.id()));
        if (everyCallHasId) {
            Map<String, AssistantMessage.ToolCall> calls = new LinkedHashMap<>();
            for (List<AssistantMessage.ToolCall> chunk : chunks) {
                for (AssistantMessage.ToolCall call : chunk) {
                    calls.merge(call.id(), call, PrivacyBufferedStreamTransformer::mergeToolCall);
                }
            }
            return List.copyOf(calls.values());
        }

        int arity = chunks.get(0).size();
        if (arity != 1 || chunks.stream().anyMatch(chunk -> chunk.size() != arity)) {
            throw correlationFailure();
        }
        List<AssistantMessage.ToolCall> calls = new ArrayList<>(arity);
        for (int index = 0; index < arity; index++) {
            AssistantMessage.ToolCall merged = chunks.get(0).get(index);
            for (int chunkIndex = 1; chunkIndex < chunks.size(); chunkIndex++) {
                merged = mergeToolCall(merged, chunks.get(chunkIndex).get(index));
            }
            calls.add(merged);
        }
        return List.copyOf(calls);
    }

    private static AssistantMessage.ToolCall mergeToolCall(
            AssistantMessage.ToolCall previous,
            AssistantMessage.ToolCall current
    ) {
        return new AssistantMessage.ToolCall(
                mergeStableField(previous.id(), current.id()),
                mergeStableField(previous.type(), current.type()),
                mergeStableField(previous.name(), current.name()),
                mergeFragment(previous.arguments(), current.arguments())
        );
    }

    private static String mergeStableField(String previous, String current) {
        if (!hasText(previous)) {
            return current;
        }
        if (!hasText(current) || previous.equals(current)) {
            return previous;
        }
        throw correlationFailure();
    }

    private static String mergeFragment(String previous, String current) {
        if (!hasText(previous)) {
            return current;
        }
        if (!hasText(current) || previous.equals(current) || previous.startsWith(current)) {
            return previous;
        }
        if (current.startsWith(previous)) {
            return current;
        }
        return previous + current;
    }

    private static AssistantMessage clearStreamedSensitiveContent(AssistantMessage message) {
        PrivacyAssistantMessageSupport.requireSupported(message, PrivacyPhase.OUTPUT_POLICY);
        Map<String, Object> clearedMetadata = PrivacyProviderTextMetadataTransformer.clearMessageTextMetadata(
                message.getMetadata()
        );
        String providerSpecificText = PrivacyAssistantMessageSupport.providerSpecificText(message);
        if (message.getText() == null
                && message.getToolCalls().isEmpty()
                && clearedMetadata == message.getMetadata()
                && (providerSpecificText == null || providerSpecificText.isEmpty())) {
            return message;
        }
        return PrivacyAssistantMessageSupport.rebuild(
                message,
                null,
                clearedMetadata,
                List.of(),
                message.getMedia(),
                null
        );
    }

    private static String choiceIndex(Generation generation) {
        ChatGenerationMetadata metadata = generation.getMetadata();
        String generationIndex = metadata == null
                ? null : canonicalizeChoiceIndex(metadata.get(CHOICE_INDEX_METADATA));
        AssistantMessage assistantMessage = generation.getOutput();
        String messageIndex = assistantMessage == null
                ? null : canonicalizeChoiceIndex(assistantMessage.getMetadata().get(CHOICE_INDEX_METADATA));
        if (generationIndex != null && messageIndex != null && !generationIndex.equals(messageIndex)) {
            throw correlationFailure();
        }
        return generationIndex != null ? generationIndex : messageIndex;
    }

    private static String canonicalizeChoiceIndex(Object index) {
        if (index == null) {
            return null;
        }
        if (index instanceof Byte || index instanceof Short
                || index instanceof Integer || index instanceof Long) {
            return "number:" + ((Number) index).longValue();
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static PrivacyGuardrailException correlationFailure() {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                PrivacyPhase.OUTPUT_POLICY,
                "Streaming response choices cannot be correlated safely"
        );
    }

    private record ChoiceKey(String value) {
    }

    private record ResponseGenerations(int responseIndex, List<Generation> generations) {
    }

    private record GenerationOccurrence(int responseIndex, int generationIndex, Generation generation) {
    }

    private enum ContentChannel {
        DEFAULT,
        THOUGHT
    }
}
