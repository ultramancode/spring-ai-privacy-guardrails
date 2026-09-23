package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local text inspection with a model adapter, complete-window coverage and bounded inference.
 * Owns the supplied adapter (including when construction fails) and its native session.
 * Do not share an adapter between inspectors. Close when no longer used.
 */
public final class OnnxContentInspector implements ContentInspector, AutoCloseable {
    private final String inspectorId;
    private final int maxWindows;
    private final OnnxInspectionModel model;
    private final OnnxInspectionRuntime runtime;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean closed;

    public OnnxContentInspector(OnnxInspectionConfig config, OnnxInspectionModel model) {
        this(null, config, model);
    }

    /** Configures an instance ID; null uses the adapter's model ID. */
    public OnnxContentInspector(String inspectorId, OnnxInspectionConfig config, OnnxInspectionModel model) {
        Objects.requireNonNull(config, "config");
        this.maxWindows = config.maxWindows();
        this.model = Objects.requireNonNull(model, "model");
        try {
            this.inspectorId = ContentSegment.requireIdentifier(inspectorId == null ? model.modelId() : inspectorId);
            this.runtime = new OnnxInspectionRuntime(config, model);
        } catch (RuntimeException | Error failure) {
            model.close();
            throw failure;
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
        Set<String> completedSegmentIds = new HashSet<>();
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
                List<Map<String, long[]>> windows = model.encode(segment.text(), maxWindows - windowCount);
                if (windows.isEmpty() || windows.size() > maxWindows - windowCount) {
                    throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
                }
                windowCount += windows.size();
                for (Map<String, long[]> window : windows) {
                    request.checkActive();
                    List<InspectionFinding> windowFindings = runtime.infer(window, segment.id(), model, request);
                    for (InspectionFinding finding : windowFindings) {
                        if (!segment.id().equals(finding.segmentId())) {
                            throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
                        }
                        findings.add(finding);
                        if (findings.size() >= 10_000) {
                            throw new InspectionException(InspectionFailureCode.LIMIT_EXCEEDED);
                        }
                    }
                }
                completedSegmentIds.add(segment.id());
            }
            request.checkActive();
            return InspectionResult.completed(completedSegmentIds, findings);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return InspectionResult.failed(InspectionFailureCode.CANCELLED, completedSegmentIds, findings);
        } catch (InspectionException ex) {
            return InspectionResult.failed(ex.failure(), completedSegmentIds, findings);
        } catch (Exception ex) {
            return InspectionResult.failed(Thread.currentThread().isInterrupted()
                    ? InspectionFailureCode.CANCELLED : InspectionFailureCode.MODEL_ERROR,
                    completedSegmentIds, findings);
        } finally {
            if (acquired) {
                lock.unlock();
            }
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (!closed) {
                closed = true;
                try {
                    model.close();
                } finally {
                    runtime.close();
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
