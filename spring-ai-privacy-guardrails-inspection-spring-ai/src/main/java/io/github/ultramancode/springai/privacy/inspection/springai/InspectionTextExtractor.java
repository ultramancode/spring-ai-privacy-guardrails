package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.ObjectReadContext;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only extraction of supported runtime payloads. Tool definitions and tool-call identifiers are excluded. */
final class InspectionTextExtractor {
    static final List<String> TEXT_METADATA_KEYS = List.of("reasoningContent", "thinking");
    private final InspectionLimits limits;
    private final ContentSegment.PrivacyProcessingStatus status;
    private final String idPrefix;
    private JsonFactory outputJsonFactory;
    private final List<ContentSegment> segments = new ArrayList<>();
    private int characters;

    InspectionTextExtractor(InspectionLimits limits, ContentSegment.PrivacyProcessingStatus status,
            String idPrefix) {
        this.limits = limits;
        this.status = status;
        this.idPrefix = idPrefix;
    }

    void message(Message message) {
        InspectionRequest.checkInterrupted();
        if (message.getClass() == ToolResponseMessage.class) {
            for (var response : ((ToolResponseMessage) message).getResponses()) {
                payload(response.responseData(), ContentSegment.Role.TOOL);
            }
        } else if (message.getClass() == UserMessage.class) {
            if (!((UserMessage) message).getMedia().isEmpty()) {
                throw unsupported();
            }
            payload(message.getText(), ContentSegment.Role.USER);
        } else if (message.getClass() == SystemMessage.class) {
            payload(message.getText(), ContentSegment.Role.SYSTEM);
        } else if (message instanceof AssistantMessage assistant) {
            requireSupportedAssistant(assistant);
            payload(assistant.getText(), ContentSegment.Role.ASSISTANT);
            String reasoning = reasoning(assistant);
            if (reasoning != null) {
                payload(reasoning, ContentSegment.Role.ASSISTANT);
            }
            metadata(assistant.getMetadata());
            for (var call : assistant.getToolCalls()) {
                payload(call.arguments(), ContentSegment.Role.ASSISTANT);
            }
        } else {
            throw unsupported();
        }
    }

    static void requireSupportedAssistant(AssistantMessage message) {
        if ((message.getClass() != AssistantMessage.class && !isDeepSeek(message))
                || !message.getMedia().isEmpty()) {
            throw unsupported();
        }
    }

    private static boolean isDeepSeek(AssistantMessage message) {
        return message.getClass().getName().equals("org.springframework.ai.deepseek.DeepSeekAssistantMessage")
                && InspectionDeepSeekSupport.isExactType(message);
    }

    static String reasoning(AssistantMessage message) {
        return isDeepSeek(message) ? InspectionDeepSeekSupport.reasoning(message) : null;
    }

    void metadata(Map<String, Object> metadata) {
        for (String key : TEXT_METADATA_KEYS) {
            if (metadata.get(key) instanceof String text) {
                payload(text, ContentSegment.Role.ASSISTANT);
            }
        }
    }

    /** Input inspectors receive each complete payload, including JSON keys, syntax and escapes. */
    void payload(String text, ContentSegment.Role role) {
        InspectionRequest.checkInterrupted();
        if (segments.size() >= limits.maxSegments()) {
            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
        }
        String content = accountPayload(text);
        segments.add(new ContentSegment(idPrefix + segments.size(), role, status, content));
    }

    /** JSON scalar values are distinct Privacy text units and must not be joined for output inspection. */
    void outputPayload(String text, ContentSegment.PrivacyProcessingStatus outputStatus) {
        String content = accountPayload(text);
        List<String> values = outputValuesOrText(content);
        if (values.size() > limits.maxSegments() - segments.size()) {
            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
        }
        for (String value : values) {
            segments.add(new ContentSegment(idPrefix + segments.size(), ContentSegment.Role.ASSISTANT,
                    outputStatus, value));
        }
    }

    private String accountPayload(String text) {
        InspectionRequest.checkInterrupted();
        String content = text == null ? "" : text;
        if (content.length() > limits.maxCharacters() - characters) {
            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
        }
        characters += content.length();
        return content;
    }

    List<ContentSegment> segments() {
        if (segments.isEmpty()) {
            payload("", ContentSegment.Role.UNKNOWN);
        }
        return List.copyOf(segments);
    }

    private List<String> outputValuesOrText(String content) {
        if (outputJsonFactory == null) {
            // Iterative parsing needs no recursive depth ceiling. The source character budget
            // also bounds nesting, token count, names and numeric lexemes without expanding numbers.
            outputJsonFactory = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(limits.maxCharacters())
                    .maxStringLength(limits.maxCharacters())
                    .maxNameLength(limits.maxCharacters())
                    .maxNumberLength(limits.maxCharacters())
                    .maxDocumentLength(limits.maxCharacters())
                    .maxTokenCount(2L * limits.maxCharacters()).build()).build();
        }
        try (JsonParser parser = outputJsonFactory.createParser(ObjectReadContext.empty(), content)) {
            List<String> values = new ArrayList<>();
            JsonToken token;
            int depth = 0;
            boolean rootSeen = false;
            while ((token = parser.nextToken()) != null) {
                InspectionRequest.checkInterrupted();
                if (depth == 0 && rootSeen) {
                    return List.of(content);
                }
                rootSeen = true;
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                    depth++;
                } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) {
                    depth--;
                } else if (token == JsonToken.VALUE_STRING || token == JsonToken.VALUE_NUMBER_INT
                        || token == JsonToken.VALUE_NUMBER_FLOAT) {
                    values.add(parser.getString());
                }
            }
            if (rootSeen && depth == 0) {
                return values.isEmpty() ? List.of("") : values;
            }
            return List.of(content);
        } catch (JacksonException ignored) {
            // Invalid JSON remains inspectable runtime text. No payload is logged or rewritten.
            return List.of(content);
        }
    }

    static InspectionException unsupported() {
        return new InspectionException(InspectionFailureCode.UNSUPPORTED_CONTENT);
    }
}
