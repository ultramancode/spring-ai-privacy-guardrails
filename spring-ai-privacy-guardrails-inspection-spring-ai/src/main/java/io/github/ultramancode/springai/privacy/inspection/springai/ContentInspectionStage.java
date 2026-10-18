package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.boundary.PiiTokenSimplifierContext;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

/** Inspects input text before each model call, including calls made after tool execution. */
final class ContentInspectionStage implements BiConsumer<ChatClientRequest, Boolean> {
    private final InspectionEnforcement enforcement;
    private final InspectionLimits limits;
    private final PrivacyProcessingStatusResolver privacyProcessingStatusResolver;

    ContentInspectionStage(InspectionService inspectionService, InspectionLimits limits,
            PrivacyProcessingStatusResolver privacyProcessingStatusResolver, InspectionObserver observer) {
        this.enforcement = new InspectionEnforcement(inspectionService, limits, observer);
        this.limits = limits;
        this.privacyProcessingStatusResolver =
                Objects.requireNonNull(privacyProcessingStatusResolver, "privacyProcessingStatusResolver");
    }

    @Override
    public void accept(ChatClientRequest chatClientRequest, Boolean streaming) {
        UnaryOperator<String> piiTokenSimplifier = PiiTokenSimplifierContext.findSimplifier(chatClientRequest);
        enforcement.inspect(() -> extractInputSegments(chatClientRequest), piiTokenSimplifier,
                new InspectionExecution(InspectionExecution.Boundary.MODEL_REQUEST, streaming));
    }

    private List<ContentSegment> extractInputSegments(ChatClientRequest chatClientRequest) {
        ContentSegment.PrivacyProcessingStatus privacyProcessingStatus = Objects.requireNonNull(
                privacyProcessingStatusResolver.resolve(chatClientRequest), "privacyProcessingStatus");
        InspectionTextExtractor inspectionTextExtractor =
                new InspectionTextExtractor(limits, privacyProcessingStatus, "segment-");
        chatClientRequest.prompt().getInstructions().forEach(inspectionTextExtractor::addMessage);
        return inspectionTextExtractor.buildSegments();
    }
}
