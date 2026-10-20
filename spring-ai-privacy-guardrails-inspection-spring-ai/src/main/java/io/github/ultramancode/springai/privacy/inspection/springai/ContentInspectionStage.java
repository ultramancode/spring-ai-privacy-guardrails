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
    private final InspectionEnforcement inspectionEnforcement;
    private final InspectionLimits inspectionLimits;
    private final PrivacyProcessingStatusResolver privacyProcessingStatusResolver;

    ContentInspectionStage(InspectionService inspectionService, InspectionLimits inspectionLimits,
        PrivacyProcessingStatusResolver privacyProcessingStatusResolver, InspectionObserver inspectionObserver) {
        this.inspectionEnforcement = new InspectionEnforcement(inspectionService, inspectionLimits, inspectionObserver);
        this.inspectionLimits = inspectionLimits;
        this.privacyProcessingStatusResolver =
                Objects.requireNonNull(privacyProcessingStatusResolver, "privacyProcessingStatusResolver");
    }

    @Override
    public void accept(ChatClientRequest chatClientRequest, Boolean streaming) {
        UnaryOperator<String> piiTokenSimplifier = PiiTokenSimplifierContext.findSimplifier(chatClientRequest);
        inspectionEnforcement.inspect(() -> extractInputSegments(chatClientRequest), piiTokenSimplifier,
            new InspectionExecution(InspectionExecution.Boundary.MODEL_REQUEST, streaming));
    }

    private List<ContentSegment> extractInputSegments(ChatClientRequest chatClientRequest) {
        ContentSegment.PrivacyProcessingStatus privacyProcessingStatus = Objects.requireNonNull(
                privacyProcessingStatusResolver.resolve(chatClientRequest), "privacyProcessingStatus");
        InspectionTextExtractor inspectionTextExtractor =
            new InspectionTextExtractor(inspectionLimits, privacyProcessingStatus, "segment-");
        chatClientRequest.prompt().getInstructions().forEach(inspectionTextExtractor::addMessage);
        return inspectionTextExtractor.buildSegments();
    }
}
