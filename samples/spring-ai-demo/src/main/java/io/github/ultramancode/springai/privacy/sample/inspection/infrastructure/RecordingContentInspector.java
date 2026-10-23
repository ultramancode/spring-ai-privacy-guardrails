package io.github.ultramancode.springai.privacy.sample.inspection.infrastructure;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.springai.InspectionExecution;

import java.util.stream.Collectors;

public final class RecordingContentInspector implements ContentInspector {

    private final ContentInspector delegate;
    private final InspectionExecution.Phase phase;
    private final InspectionEvidenceRecorder evidenceRecorder;

    public RecordingContentInspector(
            ContentInspector delegate,
            InspectionExecution.Phase phase,
            InspectionEvidenceRecorder evidenceRecorder
    ) {
        this.delegate = delegate;
        this.phase = phase;
        this.evidenceRecorder = evidenceRecorder;
    }

    @Override
    public String inspectorId() {
        return this.delegate.inspectorId();
    }

    @Override
    public boolean requiresPrivacyProcessedContent() {
        return true;
    }

    @Override
    public InspectionResult inspect(InspectionRequest request) {
        request.requirePrivacyProcessed();
        String inspectedText = request.segments().stream()
                .map(ContentSegment::text)
                .collect(Collectors.joining("\n"));
        this.evidenceRecorder.recordInspectedText(this.phase, inspectedText);
        return this.delegate.inspect(request);
    }
}
