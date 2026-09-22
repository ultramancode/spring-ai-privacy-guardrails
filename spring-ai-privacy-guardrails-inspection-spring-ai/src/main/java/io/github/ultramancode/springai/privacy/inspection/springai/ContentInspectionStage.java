package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionDecision;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailure;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.springframework.ai.chat.client.ChatClientAttributes;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Final supported text inspection before each business model call, including tool-loop continuations. */
final class ContentInspectionStage implements Consumer<ChatClientRequest> {

    private final InspectionService service;
    private final InspectionLimits limits;
    private final ContentRepresentationResolver representationResolver;
    private final Consumer<InspectionReport> observer;

    ContentInspectionStage(
            InspectionService service,
            InspectionLimits limits,
            ContentRepresentationResolver representationResolver) {
        this(service, limits, representationResolver, ignored -> {});
    }

    ContentInspectionStage(
            InspectionService service,
            InspectionLimits limits,
            ContentRepresentationResolver representationResolver,
            Consumer<InspectionReport> observer) {
        this.service = Objects.requireNonNull(service, "service");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.representationResolver = Objects.requireNonNull(representationResolver, "representationResolver");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void accept(ChatClientRequest request) {
        InspectionReport report = service.inspect(new InspectionRequest(extractSegments(request), limits));
        try {
            observer.accept(report);
        } catch (RuntimeException ignored) {
            /* Observability cannot change enforcement. */
        }
        InspectionRequest.checkInterrupted();
        if (report.decision() == InspectionDecision.BLOCK) {
            throw new InspectionBlockedException(report);
        }
    }

    private List<ContentSegment> extractSegments(ChatClientRequest request) {
        // Spring AI's terminal model advisor appends output-format instructions after
        // this boundary. Until that protocol is supported, never claim they were inspected.
        for (ChatClientAttributes attribute :
                List.of(
                        ChatClientAttributes.OUTPUT_FORMAT,
                        ChatClientAttributes.STRUCTURED_OUTPUT_SCHEMA,
                        ChatClientAttributes.STRUCTURED_OUTPUT_NATIVE)) {
            if (request.context().get(attribute.getKey()) != null) {
                throw unsupportedContent();
            }
        }
        List<ContentSegment> segments = new ArrayList<>();
        ContentSegment.Representation representation =
                Objects.requireNonNull(representationResolver.resolve(request), "representation");
        for (Message message : request.prompt().getInstructions()) {
            if (message.getClass() == ToolResponseMessage.class) {
                for (ToolResponseMessage.ToolResponse response :
                        ((ToolResponseMessage) message).getResponses()) {
                    addSegment(
                            segments,
                            response.responseData(),
                            ContentSegment.Role.TOOL,
                            ContentSegment.Source.TOOL,
                            representation);
                }
            } else if (message.getClass() == UserMessage.class) {
                if (!((UserMessage) message).getMedia().isEmpty()) {
                    throw unsupportedContent();
                }
                addSegment(
                        segments,
                        message.getText(),
                        ContentSegment.Role.USER,
                        ContentSegment.Source.UNKNOWN,
                        representation);
            } else if (message.getClass() == SystemMessage.class) {
                addSegment(
                        segments,
                        message.getText(),
                        ContentSegment.Role.SYSTEM,
                        ContentSegment.Source.UNKNOWN,
                        representation);
            } else if (message.getClass() == AssistantMessage.class) {
                if (!((AssistantMessage) message).getMedia().isEmpty()) {
                    throw unsupportedContent();
                }
                // Tool arguments are not part of the v0.4 text inspection scope.
                addSegment(
                        segments,
                        message.getText(),
                        ContentSegment.Role.ASSISTANT,
                        ContentSegment.Source.UNKNOWN,
                        representation);
            } else {
                throw unsupportedContent();
            }
        }
        return segments;
    }

    private void addSegment(
            List<ContentSegment> segments,
            String text,
            ContentSegment.Role role,
            ContentSegment.Source source,
            ContentSegment.Representation representation) {
        if (segments.size() >= limits.maxSegments()) {
            throw new InspectionException(InspectionFailure.LIMIT_EXCEEDED);
        }
        segments.add(
                new ContentSegment(
                        "segment-" + segments.size(),
                        source,
                        role,
                        representation,
                        text == null ? "" : text));
    }

    private static InspectionException unsupportedContent() {
        return new InspectionException(InspectionFailure.UNSUPPORTED_CONTENT);
    }

}
