package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Tokenizes validated Tool Search arguments while preserving callbacks and tool-call identity. */
final class PrivacyToolSearchArgumentProtector {

    private final PrivacyService privacyService;

    PrivacyToolSearchArgumentProtector(PrivacyService privacyService) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
    }

    static Set<String> toolSearchToolNames(ChatClientRequest request) {
        if (!(request.prompt().getOptions() instanceof ToolCallingChatOptions options)
                || options.getToolCallbacks() == null) {
            return Set.of();
        }
        return options.getToolCallbacks().stream()
                .filter(callback -> SpringAiToolSearchSupport.isToolSearchToolCallback(callback, options))
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toUnmodifiableSet());
    }

    ChatClientResponse protect(
            PrivacyContextHandle handle,
            ChatClientResponse response,
            Set<String> toolSearchToolNames
    ) {
        ChatResponse originalResponse = response.chatResponse();
        if (toolSearchToolNames.isEmpty() || originalResponse == null) {
            return response;
        }

        List<Generation> updatedGenerations = new ArrayList<>(originalResponse.getResults().size());
        boolean responseChanged = false;
        for (Generation generation : originalResponse.getResults()) {
            AssistantMessage message = generation.getOutput();
            if (message == null) {
                updatedGenerations.add(generation);
                continue;
            }

            AssistantMessage updatedMessage = tokenizeSearchArgumentsInMessage(handle, message, toolSearchToolNames);
            if (updatedMessage == message) {
                updatedGenerations.add(generation);
                continue;
            }

            updatedGenerations.add(new Generation(updatedMessage, generation.getMetadata()));
            responseChanged = true;
        }

        ChatResponseMetadata metadata = originalResponse.getMetadata();
        List<AssistantMessage.ToolCall> metadataToolCalls = PrivacyToolCallMetadataReader.read(
                originalResponse,
                PrivacyPhase.TOOL_INPUT
        );
        List<AssistantMessage.ToolCall> updatedMetadataToolCalls = tokenizeSearchArguments(
                handle,
                metadataToolCalls,
                toolSearchToolNames
        );
        if (!updatedMetadataToolCalls.equals(metadataToolCalls)) {
            metadata = copyMetadataWithToolCalls(metadata, updatedMetadataToolCalls);
            responseChanged = true;
        }

        if (!responseChanged) {
            return response;
        }
        ChatResponse updatedResponse = new ChatResponse(updatedGenerations, metadata);
        return response.mutate().chatResponse(updatedResponse).build();
    }

    /** Returns the original message instance when no search arguments change. */
    private AssistantMessage tokenizeSearchArgumentsInMessage(
            PrivacyContextHandle handle,
            AssistantMessage message,
            Set<String> toolSearchToolNames
    ) {
        List<AssistantMessage.ToolCall> originalToolCalls = message.getToolCalls();
        List<AssistantMessage.ToolCall> updatedToolCalls = tokenizeSearchArguments(
                handle,
                originalToolCalls,
                toolSearchToolNames
        );
        if (updatedToolCalls.equals(originalToolCalls)) {
            return message;
        }
        return PrivacyAssistantMessageSupport.rebuild(
                message,
                message.getText(),
                message.getMetadata(),
                updatedToolCalls,
                message.getMedia(),
                PrivacyAssistantMessageSupport.providerSpecificText(message)
        );
    }

    private List<AssistantMessage.ToolCall> tokenizeSearchArguments(
            PrivacyContextHandle handle,
            List<AssistantMessage.ToolCall> toolCalls,
            Set<String> toolSearchToolNames
    ) {
        return toolCalls.stream().map(toolCall -> {
            if (!toolSearchToolNames.contains(toolCall.name())) {
                return toolCall;
            }
            // Expects complete JSON from the model adapter, including in streaming responses.
            String tokenizedArguments = PrivacyJsonPayloadTransformer.tokenize(
                    this.privacyService,
                    handle,
                    toolCall.arguments(),
                    PrivacyPhase.TOOL_INPUT,
                    true
            );
            if (tokenizedArguments.equals(toolCall.arguments())) {
                return toolCall;
            }
            return new AssistantMessage.ToolCall(
                    toolCall.id(),
                    toolCall.type(),
                    toolCall.name(),
                    tokenizedArguments
            );
        }).toList();
    }

    private static ChatResponseMetadata copyMetadataWithToolCalls(
            ChatResponseMetadata metadata,
            List<AssistantMessage.ToolCall> updatedToolCalls
    ) {
        ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder()
                .id(metadata.getId())
                .model(metadata.getModel())
                .usage(metadata.getUsage())
                .rateLimit(metadata.getRateLimit())
                .promptMetadata(metadata.getPromptMetadata());
        metadata.entrySet().forEach(entry -> builder.keyValue(entry.getKey(), entry.getValue()));
        return builder.keyValue(PrivacyToolCallMetadataReader.TOOL_CALLS_KEY, updatedToolCalls).build();
    }
}
