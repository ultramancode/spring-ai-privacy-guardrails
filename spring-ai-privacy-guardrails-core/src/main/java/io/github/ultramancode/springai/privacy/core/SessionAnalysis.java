package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Looks up core-produced analysis in one session. Analyzer work runs outside context locks. */
final class SessionAnalysis {

    private final PiiAnalysisCoordinator coordinator;

    SessionAnalysis(PiiAnalysisCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    PiiAnalysisCoordinator.AnalysisEvidence analyzeEvidence(String text, PrivacyContext context) {
        this.coordinator.requireTextInputWithinLimit(text);
        context.requireActive();
        rejectInterrupted();
        if (text == null || text.isBlank()) {
            return this.coordinator.analyzeEvidence(text);
        }
        PiiAnalysisCoordinator.AnalysisEvidence retained = context.analysisFor(text);
        if (retained != null) {
            context.requireActive();
            return retained;
        }
        PiiAnalysisCoordinator.AnalysisEvidence analyzed = this.coordinator.analyzeEvidence(text);
        rejectInterrupted();
        context.retainAnalysis(text, analyzed);
        return analyzed;
    }

    List<PiiAnalysisCoordinator.AnalysisEvidence> analyzeSegmentsEvidence(
            List<String> texts, PrivacyContext context) {
        Objects.requireNonNull(texts, "texts must not be null");
        if (texts.size() > this.coordinator.processingLimits().maxValueTreeNodes()) {
            throw limitExceeded("PII analysis exceeded the logical segment limit");
        }
        context.requireActive();
        rejectInterrupted();
        Map<String, PiiAnalysisCoordinator.AnalysisEvidence> evidenceByText = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        List<String> uncachedTexts = new ArrayList<>();
        long inputCharacters = 0;
        for (String text : texts) {
            rejectInterrupted();
            if (text != null) {
                inputCharacters += text.length();
                if (inputCharacters > this.coordinator.processingLimits().maxTextCharacters()) {
                    throw limitExceeded("PII analysis input exceeded the configured text limit");
                }
            }
            if (text == null || text.isBlank() || evidenceByText.containsKey(text)) {
                if (text != null && !text.isBlank()) {
                    occurrences.merge(text, 1, Integer::sum);
                }
                continue;
            }
            occurrences.put(text, 1);
            PiiAnalysisCoordinator.AnalysisEvidence retained = context.analysisFor(text);
            if (retained != null) {
                evidenceByText.put(text, retained);
            } else {
                evidenceByText.put(text, null);
                uncachedTexts.add(text);
            }
        }
        long accumulatedEvidence = 0;
        for (Map.Entry<String, PiiAnalysisCoordinator.AnalysisEvidence> entry : evidenceByText.entrySet()) {
            if (entry.getValue() != null) {
                accumulatedEvidence += (long) entry.getValue().evidenceCount()
                        * occurrences.get(entry.getKey());
                this.coordinator.requireEvidenceWithinLimit(accumulatedEvidence);
            }
        }
        analyzeUncachedSegments(uncachedTexts, occurrences, evidenceByText, accumulatedEvidence);
        List<PiiAnalysisCoordinator.AnalysisEvidence> results = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                results.add(this.coordinator.analyzeEvidence(text));
            } else {
                results.add(evidenceByText.get(text));
            }
        }
        rejectInterrupted();
        context.requireActive();
        boolean complete = uncachedTexts.stream()
                .allMatch(text -> evidenceByText.get(text).result().failures().isEmpty());
        if (complete) {
            for (String text : uncachedTexts) {
                context.retainAnalysis(text, evidenceByText.get(text));
            }
        }
        return List.copyOf(results);
    }

    private void analyzeUncachedSegments(
            List<String> uncachedTexts,
            Map<String, Integer> occurrences,
            Map<String, PiiAnalysisCoordinator.AnalysisEvidence> evidenceByText,
            long accumulatedEvidence
    ) {
        int index = 0;
        while (index < uncachedTexts.size()) {
            List<String> batch = new ArrayList<>();
            long batchCharacters = 0;
            while (index < uncachedTexts.size()
                    && batch.size() < this.coordinator.processingLimits().maxAnalysisSegments()) {
                String next = uncachedTexts.get(index);
                if (!batch.isEmpty() && batchCharacters + next.length() >
                        Math.min(32_768, this.coordinator.processingLimits().maxTextCharacters())) {
                    break;
                }
                batch.add(next);
                batchCharacters += next.length();
                index++;
            }
            List<PiiAnalysisCoordinator.AnalysisEvidence> analyzed =
                    this.coordinator.analyzeSegmentsEvidence(batch);
            for (int resultIndex = 0; resultIndex < batch.size(); resultIndex++) {
                String source = batch.get(resultIndex);
                PiiAnalysisCoordinator.AnalysisEvidence evidence = analyzed.get(resultIndex);
                evidenceByText.put(source, evidence);
                accumulatedEvidence += (long) evidence.evidenceCount() * occurrences.get(source);
                this.coordinator.requireEvidenceWithinLimit(accumulatedEvidence);
            }
        }
    }

    private static PrivacyGuardrailException limitExceeded(String message) {
        return new PrivacyGuardrailException(
                PrivacyFailureCode.PAYLOAD_LIMIT_EXCEEDED, PrivacyPhase.ANALYSIS, message);
    }

    private static void rejectInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.ANALYSIS_INTERRUPTED,
                    PrivacyPhase.ANALYSIS,
                    "PII analysis interrupted");
        }
    }
}
