package io.github.ultramancode.springai.privacy.inspection.springai;

import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.springframework.ai.chat.client.ChatClientRequest;

import java.util.Objects;
import java.util.function.Consumer;

/** Final supported runtime content inspection before each model call, including tool continuations. */
final class ContentInspectionStage implements Consumer<ChatClientRequest> {
    private final InspectionEnforcement enforcement;
    private final InspectionLimits limits;
    private final PrivacyProcessingStatusResolver resolver;

    ContentInspectionStage(InspectionService service, InspectionLimits limits,
            PrivacyProcessingStatusResolver resolver, InspectionObserver observer) {
        this.enforcement = new InspectionEnforcement(service, limits, observer);
        this.limits = limits;
        this.resolver = Objects.requireNonNull(resolver, "privacyProcessingStatusResolver");
    }

    @Override
    public void accept(ChatClientRequest request) {
        enforcement.inspect(() -> {
            var status = Objects.requireNonNull(resolver.resolve(request), "privacyProcessingStatus");
            var extractor = new InspectionTextExtractor(limits, status, "segment-");
            request.prompt().getInstructions().forEach(extractor::message);
            return extractor.segments();
        });
    }
}
