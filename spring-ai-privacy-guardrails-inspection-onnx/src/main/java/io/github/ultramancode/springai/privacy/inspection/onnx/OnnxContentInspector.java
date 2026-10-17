package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionIdentifiers;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Inspects text locally on the CPU with an ONNX text classification model.
 * Each segment is split into token windows with configurable overlap.
 *
 * <p>Returns at most one finding per configured label and segment,
 * with the highest score that meets the threshold.
 * A segment is complete after all its windows are inspected.
 * If inspection fails, the returned result includes any findings already collected.
 *
 * <p>Instances are thread-safe. Concurrent calls run one at a time, with waiting counted against the deadline.
 * Deadline and interruption checks limit further work but do not cancel active native calls.
 * Call {@link #close()} to release the tokenizer and ONNX session.
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
        this.inspectorId = InspectionIdentifiers.requireValid(inspectorId);
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
        Set<String> completedSegmentIds = new HashSet<>();
        Map<FindingKey, InspectionFinding> requestFindings = new LinkedHashMap<>();
        boolean acquired = false;
        try {
            acquired = lock.tryLock(request.remaining().toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) {
                throw new InspectionException(InspectionFailureCode.TIMEOUT);
            }
            if (closed) {
                throw new InspectionException(InspectionFailureCode.INSPECTOR_CLOSED);
            }
            int windowCount = 0;
            for (ContentSegment segment : request.segments()) {
                request.checkActive();
                if (windowCount == maxWindows) {
                    throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
                }
                List<Encoding> windows = tokenizer.encodeWindows(segment.text());
                request.checkActive();
                for (Encoding window : windows) {
                    request.checkActive();
                    if (windowCount == maxWindows) {
                        throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
                    }
                    double[] scores = classifier.classify(window);
                    windowCount++;
                    collectFindings(segment.id(), scores, requestFindings);
                    InspectionRequest.checkInterrupted();
                }
                completedSegmentIds.add(segment.id());
            }
            InspectionRequest.checkInterrupted();
            return InspectionResult.completed(completedSegmentIds, List.copyOf(requestFindings.values()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return InspectionResult.failed(
                    InspectionFailureCode.CANCELLED, completedSegmentIds, List.copyOf(requestFindings.values()));
        } catch (InspectionException ex) {
            return InspectionResult.failed(ex.failureCode(), completedSegmentIds, List.copyOf(requestFindings.values()));
        } catch (RuntimeException ex) {
            return InspectionResult.failed(
                    InspectionFailureCode.INVALID_RESULT, completedSegmentIds, List.copyOf(requestFindings.values()));
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }

    private void collectFindings(String segmentId, double[] scores,
            Map<FindingKey, InspectionFinding> requestFindings) {
        for (OnnxClassificationConfig.Label label : labels) {
            double score = scores[label.index()];
            if (score < label.threshold()) {
                continue;
            }
            FindingKey key = new FindingKey(segmentId, label.code());
            InspectionFinding existingFinding = requestFindings.get(key);
            if (existingFinding == null || score > existingFinding.score()) {
                requestFindings.put(key, new InspectionFinding(segmentId, label.category(), label.code(), score));
            }
        }
    }

    /**
     * Waits for an active inspection to finish, then closes the tokenizer and ONNX session once.
     * Further inspection attempts fail with {@link InspectionFailureCode#INSPECTOR_CLOSED}.
     */
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

    private record FindingKey(String segmentId, String code) {}
}
