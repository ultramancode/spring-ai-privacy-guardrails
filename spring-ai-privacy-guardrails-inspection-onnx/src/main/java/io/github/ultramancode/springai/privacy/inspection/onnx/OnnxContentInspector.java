package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local, thread-safe CPU inspection with overlapping token windows. Owns its tokenizer and ONNX session.
 * One finding per mapped label and segment retains the maximum qualifying window score.
 * Partial findings survive failure. Coverage is complete only after every window finishes.
 * Concurrent calls wait within their shared deadline. Activity is checked between synchronous calls,
 * without forcibly cancelling native work. Close when no longer used.
 */
public final class OnnxContentInspector implements ContentInspector, AutoCloseable {

    private final String inspectorId;
    private final int maxWindows;
    private final List<OnnxClassificationConfig.Label> labels;
    private final TokenWindowTokenizer tokenizer;
    private final OnnxSequenceClassifier classifier;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean closed;

    public OnnxContentInspector(
            String inspectorId, OnnxInspectionConfig config, OnnxClassificationConfig classification) {
        this.inspectorId = ContentSegment.requireIdentifier(inspectorId);
        this.maxWindows = Objects.requireNonNull(config, "config").maxWindows();
        this.labels = Objects.requireNonNull(classification, "classification").labels();
        tokenizer = new TokenWindowTokenizer(classification);
        try {
            classifier = new OnnxSequenceClassifier(config, classification);
        } catch (RuntimeException | Error ex) {
            tokenizer.close();
            throw ex;
        }
    }

    @Override
    public String inspectorId() {
        return inspectorId;
    }

    @Override
    public boolean requiresPrivacyProcessedContent() {
        return false;
    }

    @Override
    public InspectionResult inspect(InspectionRequest request) {
        Objects.requireNonNull(request, "request");
        Set<String> completed = new HashSet<>();
        List<InspectionFinding> findings = new ArrayList<>();
        boolean acquired = false;
        try {
            acquired = lock.tryLock(request.remaining().toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) {
                throw new InspectionException(InspectionFailureCode.TIMEOUT);
            }
            if (closed) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            int windowCount = 0;
            for (ContentSegment segment : request.segments()) {
                request.checkActive();
                if (windowCount == maxWindows) {
                    throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
                }
                List<Encoding> windows = tokenizer.encodeWindows(segment.text());
                request.checkActive();
                Map<String, Integer> findingPositions = new HashMap<>();
                for (Encoding window : windows) {
                    request.checkActive();
                    if (windowCount == maxWindows) {
                        throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
                    }
                    double[] scores = classifier.classify(window);
                    request.checkActive();
                    windowCount++;
                    collectFindings(segment.id(), scores, findingPositions, findings, request.limits().maxFindings());
                }
                completed.add(segment.id());
            }
            request.checkActive();
            return InspectionResult.completed(completed, findings);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return InspectionResult.failed(InspectionFailureCode.CANCELLED, completed, findings);
        } catch (InspectionException ex) {
            return InspectionResult.failed(request.resolveFailure(ex.failure()), completed, findings);
        } catch (RuntimeException ex) {
            return InspectionResult.failed(
                    request.resolveFailure(InspectionFailureCode.INVALID_RESULT), completed, findings);
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }

    private void collectFindings(String segmentId, double[] scores, Map<String, Integer> findingPositions,
            List<InspectionFinding> findings, int limit) {
        for (OnnxClassificationConfig.Label label : labels) {
            double score = scores[label.index()];
            if (score < label.threshold()) {
                continue;
            }
            Integer position = findingPositions.get(label.code());
            if (position == null) {
                if (findings.size() == limit) {
                    throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
                }
                findingPositions.put(label.code(), findings.size());
                findings.add(new InspectionFinding(segmentId, label.category(), label.code(), score));
            } else if (score > findings.get(position).score()) {
                findings.set(position, new InspectionFinding(segmentId, label.category(), label.code(), score));
            }
        }
    }

    /** Waits for an active inspection to finish, then closes the tokenizer and ONNX session once. */
    @Override
    public void close() {
        lock.lock();
        try {
            if (!closed) {
                closed = true;
                try {
                    tokenizer.close();
                } finally {
                    classifier.close();
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
