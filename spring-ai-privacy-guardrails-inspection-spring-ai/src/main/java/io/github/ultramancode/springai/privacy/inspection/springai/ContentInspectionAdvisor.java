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
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.ChatModelCallAdvisor;
import org.springframework.ai.chat.client.advisor.ChatModelStreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Final supported text inspection before each business model call, including tool-loop continuations. */
public final class ContentInspectionAdvisor implements CallAdvisor, StreamAdvisor {

    public static final int DEFAULT_ORDER = Integer.MAX_VALUE - 1;
    private final InspectionService service;
    private final InspectionLimits limits;
    private final ContentRepresentationResolver representationResolver;
    private final Consumer<InspectionReport> observer;

    public ContentInspectionAdvisor(
            InspectionService service,
            InspectionLimits limits,
            ContentRepresentationResolver representationResolver) {
        this(service, limits, representationResolver, ignored -> {});
    }

    public ContentInspectionAdvisor(
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
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        validatePositionBeforeModel(chain.getCallAdvisors());
        inspect(request);
        InspectionRequest.checkInterrupted();
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(
            ChatClientRequest request, StreamAdvisorChain chain) {
        // Work starts per subscription. Cancellation interrupts blocking HTTP/ONNX inspection;
        // flatMapMany never subscribes to the business model before inspection completes.
        return Mono.fromCallable(
                        () -> {
                            validatePositionBeforeModel(chain.getStreamAdvisors());
                            inspect(request);
                            return request;
                        })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(
                        checked -> {
                            InspectionRequest.checkInterrupted();
                            return chain.nextStream(checked);
                        });
    }

    private void inspect(ChatClientRequest request) {
        InspectionReport report = service.inspect(new InspectionRequest(extractSegments(request), limits));
        try {
            observer.accept(report);
        } catch (RuntimeException ignored) {
            /* Observability cannot change enforcement. */
        }
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

    private void validatePositionBeforeModel(List<? extends Advisor> advisors) {
        int index = advisors.indexOf(this);
        if (index < 0) {
            throw new InspectionException(InspectionFailure.CONFIGURATION);
        }
        validateModelOnlyTail(advisors.subList(index + 1, advisors.size()));
    }

    static void validateModelOnlyTail(List<? extends Advisor> tail) {
        if (tail.size() != 1
                || !(tail.get(0).getClass() == ChatModelCallAdvisor.class
                        || tail.get(0).getClass() == ChatModelStreamAdvisor.class)) {
            throw new InspectionException(InspectionFailure.CONFIGURATION);
        }
    }

    private static InspectionException unsupportedContent() {
        return new InspectionException(InspectionFailure.UNSUPPORTED_CONTENT);
    }

    @Override
    public String getName() {
        return "ContentInspectionAdvisor";
    }

    @Override
    public int getOrder() {
        return DEFAULT_ORDER;
    }
}
