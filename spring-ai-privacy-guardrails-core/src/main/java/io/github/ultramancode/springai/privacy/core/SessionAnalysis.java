package io.github.ultramancode.springai.privacy.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reuses successful analysis for identical source text within a session.
 * Runs analyzers without holding the context lock.
 */
final class SessionAnalysis {

    private final PiiAnalysisCoordinator coordinator;

    SessionAnalysis(PiiAnalysisCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    PiiAnalysisResult analyze(String text, PrivacyContext context) {
        this.coordinator.requireTextInputWithinLimit(text);
        context.requireActive();
        rejectInterrupted();
        if (text == null || text.isBlank()) {
            return this.coordinator.analyzeEvidence(text).result();
        }
        PiiAnalysisCoordinator.AnalysisEvidence retained = context.cachedAnalysisFor(text);
        if (retained != null) {
            context.requireActive();
            return retained.result();
        }
        PiiAnalysisCoordinator.AnalysisEvidence analyzed = this.coordinator.analyzeEvidence(text);
        rejectInterrupted();
        context.retainAnalysis(text, analyzed);
        return analyzed.result();
    }

    /**
     * Returns results in input order, reusing successful analysis for identical text.
     * Callers enforce input counts while this method bounds text and newly collected analyzer spans.
     */
    List<PiiAnalysisResult> analyzeSegments(
            List<String> texts, PrivacyContext context) {
        Objects.requireNonNull(texts, "texts must not be null");
        context.requireActive();
        rejectInterrupted();
        Map<String, PiiAnalysisCoordinator.AnalysisEvidence> evidenceByText = new LinkedHashMap<>();
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
            if (text == null || text.isBlank()) {
                continue;
            }
            if (evidenceByText.containsKey(text)) {
                continue;
            }
            PiiAnalysisCoordinator.AnalysisEvidence retained = context.cachedAnalysisFor(text);
            if (retained != null) {
                evidenceByText.put(text, retained);
            } else {
                evidenceByText.put(text, null);
                uncachedTexts.add(text);
            }
        }
        analyzeUncachedSegments(uncachedTexts, evidenceByText);
        List<PiiAnalysisResult> results = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                // Keep an empty result for each null or blank input without invoking analyzers
                results.add(this.coordinator.analyzeEvidence(text).result());
            } else {
                results.add(evidenceByText.get(text).result());
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
            Map<String, PiiAnalysisCoordinator.AnalysisEvidence> evidenceByText
    ) {
        int maxBatchSegments = this.coordinator.processingLimits().maxAnalysisSegments();
        long collectedEvidence = 0;
        int batchStart = 0;
        while (batchStart < uncachedTexts.size()) {
            int batchSize = Math.min(maxBatchSegments, uncachedTexts.size() - batchStart);
            int batchEnd = batchStart + batchSize;
            List<String> batch = uncachedTexts.subList(batchStart, batchEnd);
            List<PiiAnalysisCoordinator.AnalysisEvidence> analyzed =
                    this.coordinator.analyzeSegmentsEvidence(batch);
            for (int resultIndex = 0; resultIndex < batch.size(); resultIndex++) {
                String source = batch.get(resultIndex);
                PiiAnalysisCoordinator.AnalysisEvidence evidence = analyzed.get(resultIndex);
                evidenceByText.put(source, evidence);
                collectedEvidence += evidence.evidenceCount();
                this.coordinator.requireEvidenceWithinLimit(collectedEvidence);
            }
            batchStart = batchEnd;
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
