package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyContextHandle;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

final class PrivacyMessageTransformer {

    private final PrivacyService privacyService;

    PrivacyMessageTransformer(PrivacyService privacyService) {
        this.privacyService = Objects.requireNonNull(privacyService, "privacyService must not be null");
    }

    ChatClientRequest tokenize(PrivacyContextHandle handle, ChatClientRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        Prompt prompt = request.prompt();
        List<Message> messages = tokenize(handle, prompt.getInstructions());
        if (messages.equals(prompt.getInstructions())) {
            return request;
        }
        return request.mutate().prompt(new Prompt(messages, prompt.getOptions())).build();
    }

    List<Message> tokenize(PrivacyContextHandle handle, List<Message> messages) {
        Objects.requireNonNull(messages, "messages must not be null");
        List<Message> tokenizedMessages = new ArrayList<>(messages.size());
        for (Message message : messages) {
            tokenizedMessages.add(tokenize(handle, message));
        }
        return List.copyOf(tokenizedMessages);
    }

    private Message tokenize(PrivacyContextHandle handle, Message message) {
        Objects.requireNonNull(message, "message must not be null");
        if (message instanceof UserMessage userMessage
                && message.getClass() == UserMessage.class) {
            String tokenized = tokenizeText(handle, userMessage.getText());
            return Objects.equals(tokenized, userMessage.getText())
                    ? userMessage
                    : userMessage.mutate().text(tokenized).build();
        }
        if (message instanceof SystemMessage systemMessage
                && message.getClass() == SystemMessage.class) {
            String tokenized = tokenizeText(handle, systemMessage.getText());
            return Objects.equals(tokenized, systemMessage.getText())
                    ? systemMessage
                    : systemMessage.mutate().text(tokenized).build();
        }
        if (message instanceof AssistantMessage assistantMessage) {
            PrivacyAssistantMessageSupport.requireSupported(
                    assistantMessage,
                    PrivacyPhase.TOKENIZATION
            );
            return tokenizeAssistantMessage(handle, assistantMessage);
        }
        if (message instanceof ToolResponseMessage toolResponseMessage
                && message.getClass() == ToolResponseMessage.class) {
            return tokenizeToolResponseMessage(handle, toolResponseMessage);
        }
        throw unsupportedMessage(PrivacyPhase.TOKENIZATION);
    }

    private Message tokenizeAssistantMessage(PrivacyContextHandle handle, AssistantMessage message) {
        String content = tokenizeText(handle, message.getText(), false);
        String providerSpecificText = PrivacyAssistantMessageSupport.providerSpecificText(message);
        String tokenizedProviderSpecificText = tokenizeText(handle, providerSpecificText, false);
        var metadata = PrivacyProviderTextMetadataTransformer.transformMessageMetadata(
                message.getMetadata(),
                text -> tokenizeText(handle, text, false)
        );
        List<AssistantMessage.ToolCall> toolCalls = message.getToolCalls().stream()
                .map(toolCall -> new AssistantMessage.ToolCall(
                        toolCall.id(),
                        toolCall.type(),
                        toolCall.name(),
                        tokenizeText(handle, toolCall.arguments(), true)
                ))
                .toList();
        if (Objects.equals(content, message.getText())
                && Objects.equals(tokenizedProviderSpecificText, providerSpecificText)
                && metadata == message.getMetadata()
                && toolCalls.equals(message.getToolCalls())) {
            return message;
        }
        return PrivacyAssistantMessageSupport.rebuild(
                message,
                content,
                metadata,
                toolCalls,
                message.getMedia(),
                tokenizedProviderSpecificText
        );
    }

    static AssistantMessage transformAssistantMessage(
            AssistantMessage message,
            UnaryOperator<String> textTransformer
    ) {
        Objects.requireNonNull(message, "message must not be null");
        Objects.requireNonNull(textTransformer, "textTransformer must not be null");
        PrivacyAssistantMessageSupport.requireSupported(message, PrivacyPhase.OUTPUT_POLICY);
        String content = textTransformer.apply(message.getText());
        String providerSpecificText = PrivacyAssistantMessageSupport.providerSpecificText(message);
        String transformedProviderSpecificText = providerSpecificText == null
                ? null
                : textTransformer.apply(providerSpecificText);
        var metadata = PrivacyProviderTextMetadataTransformer.transformMessageMetadata(
                message.getMetadata(),
                textTransformer
        );
        List<AssistantMessage.ToolCall> toolCalls = message.getToolCalls().stream()
                .map(toolCall -> new AssistantMessage.ToolCall(
                        toolCall.id(),
                        toolCall.type(),
                        toolCall.name(),
                        textTransformer.apply(toolCall.arguments())
                ))
                .toList();
        if (Objects.equals(content, message.getText())
                && Objects.equals(transformedProviderSpecificText, providerSpecificText)
                && metadata == message.getMetadata()
                && toolCalls.equals(message.getToolCalls())) {
            return message;
        }
        return PrivacyAssistantMessageSupport.rebuild(
                message,
                content,
                metadata,
                toolCalls,
                message.getMedia(),
                transformedProviderSpecificText
        );
    }

    private Message tokenizeToolResponseMessage(PrivacyContextHandle handle, ToolResponseMessage message) {
        List<ToolResponseMessage.ToolResponse> responses = message.getResponses().stream()
                .map(response -> new ToolResponseMessage.ToolResponse(
                        response.id(),
                        response.name(),
                        tokenizeText(handle, response.responseData(), false)
                ))
                .toList();
        if (responses.equals(message.getResponses())) {
            return message;
        }
        return ToolResponseMessage.builder()
                .responses(responses)
                .metadata(message.getMetadata())
                .build();
    }

    private String tokenizeText(PrivacyContextHandle handle, String text) {
        return tokenizeText(handle, text, false);
    }

    private String tokenizeText(
            PrivacyContextHandle handle,
            String text,
            boolean requireValidJson
    ) {
        if (text == null) {
            return text;
        }
        return PrivacyJsonPayloadTransformer.tokenize(
                this.privacyService,
                handle,
                text,
                PrivacyPhase.TOKENIZATION,
                requireValidJson
        );
    }

    private static PrivacyGuardrailException unsupportedMessage(PrivacyPhase phase) {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                phase,
                "Unsupported Spring AI Message implementation"
        );
    }
}
