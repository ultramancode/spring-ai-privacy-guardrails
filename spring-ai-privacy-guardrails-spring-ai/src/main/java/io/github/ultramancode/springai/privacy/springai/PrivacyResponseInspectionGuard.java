package io.github.ultramancode.springai.privacy.springai;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Per-response counter that rejects oversized call and stream content before privacy processing. */
final class PrivacyResponseInspectionGuard {

    private static final String SAFE_FAILURE_MESSAGE =
            "Model response exceeded the configured privacy inspection limit";

    private final PrivacyResponseInspectionLimits limits;
    private final boolean rejectCrossChannelDuplicateToolCalls;
    private long frameCount;
    private long characterCount;
    private long mediaBytes;
    private final Set<ToolCallIdentity> messageToolCallIdentities = new HashSet<>();
    private final Set<ToolCallIdentity> metadataToolCallIdentities = new HashSet<>();
    private final PrivacyPhase phase;

    PrivacyResponseInspectionGuard(PrivacyResponseInspectionLimits limits) {
        this(limits, PrivacyPhase.OUTPUT_POLICY, false);
    }

    PrivacyResponseInspectionGuard(
            PrivacyResponseInspectionLimits limits,
            PrivacyPhase phase,
            boolean rejectCrossChannelDuplicateToolCalls
    ) {
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
        this.rejectCrossChannelDuplicateToolCalls = rejectCrossChannelDuplicateToolCalls;
    }

    void accept(ChatClientResponse response) {
        if (++this.frameCount > this.limits.maxStreamFrames()) {
            throw limitExceeded();
        }
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse == null) {
            return;
        }
        for (Generation generation : chatResponse.getResults()) {
            addCharacters(PrivacyProviderTextMetadataTransformer.textCharacterCount(generation.getMetadata()));
            AssistantMessage message = generation.getOutput();
            if (message == null) {
                continue;
            }
            PrivacyAssistantMessageSupport.requireSupported(message, this.phase);
            addCharacters(length(message.getText()));
            addCharacters(length(PrivacyAssistantMessageSupport.providerSpecificText(message)));
            addCharacters(PrivacyProviderTextMetadataTransformer.textCharacterCount(message.getMetadata()));
            for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
                acceptToolCallIdentity(
                        toolCall,
                        this.messageToolCallIdentities,
                        this.metadataToolCallIdentities
                );
                addCharacters(length(toolCall.id()));
                addCharacters(length(toolCall.type()));
                addCharacters(length(toolCall.name()));
                addCharacters(length(toolCall.arguments()));
            }
            for (Media media : message.getMedia()) {
                addMediaBytes(mediaBytes(media));
            }
        }
        for (AssistantMessage.ToolCall toolCall : PrivacyToolCallMetadataReader.read(
                chatResponse,
                this.phase
        )) {
            acceptToolCallIdentity(
                    toolCall,
                    this.metadataToolCallIdentities,
                    this.messageToolCallIdentities
            );
            addCharacters(length(toolCall.id()));
            addCharacters(length(toolCall.type()));
            addCharacters(length(toolCall.name()));
            addCharacters(length(toolCall.arguments()));
        }
    }

    private void addCharacters(long additional) {
        this.characterCount = safeAdd(this.characterCount, additional);
        if (this.characterCount > this.limits.maxCharacters()) {
            throw limitExceeded();
        }
    }

    private void addMediaBytes(long additional) {
        this.mediaBytes = safeAdd(this.mediaBytes, additional);
        if (this.mediaBytes > this.limits.maxMediaBytes()) {
            throw limitExceeded();
        }
    }

    private long safeAdd(long current, long additional) {
        if (additional < 0 || current > Long.MAX_VALUE - additional) {
            throw limitExceeded();
        }
        return current + additional;
    }

    private long mediaBytes(Media media) {
        Object mediaData = media.getData();
        if (mediaData instanceof byte[] bytes) {
            return bytes.length;
        }
        if (mediaData instanceof CharSequence characters) {
            // A Java char is one UTF-16 code unit; four bytes each safely overestimates UTF-8
            // size without allocating an encoded byte array.
            long length = characters.length();
            return length > Long.MAX_VALUE / 4 ? Long.MAX_VALUE : length * 4;
        }
        throw limitExceeded();
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private void acceptToolCallIdentity(
            AssistantMessage.ToolCall toolCall,
            Set<ToolCallIdentity> currentChannel,
            Set<ToolCallIdentity> oppositeChannel
    ) {
        if (toolCall == null) {
            throw malformedToolCall();
        }
        if (!this.rejectCrossChannelDuplicateToolCalls) {
            return;
        }
        ToolCallIdentity identity = toolCall.id() == null || toolCall.id().isBlank()
                ? new ToolCallIdentity(null, toolCall.type(), toolCall.name(), toolCall.arguments())
                : new ToolCallIdentity(toolCall.id(), null, null, null);
        if (oppositeChannel.contains(identity)) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                    this.phase,
                    "Duplicate tool call rejected by privacy guardrail"
            );
        }
        currentChannel.add(identity);
    }

    private PrivacyGuardrailException malformedToolCall() {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.TRANSFORMATION_CONFLICT,
                this.phase,
                "Tool control field is invalid"
        );
    }

    private PrivacyGuardrailException limitExceeded() {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.RESPONSE_INSPECTION_LIMIT_EXCEEDED,
                this.phase,
                SAFE_FAILURE_MESSAGE
        );
    }

    private record ToolCallIdentity(String id, String type, String name, String arguments) {
    }
}
