package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailure;
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
    private final OnnxInspectionModel model;
    private final OnnxInspectionRuntime runtime;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean closed;

    public OnnxContentInspector(OnnxInspectionConfig config, OnnxInspectionModel model) {
        Objects.requireNonNull(config, "config");
        this.model = Objects.requireNonNull(model, "model");
        try {
            this.runtime = new OnnxInspectionRuntime(config, model);
        } catch (RuntimeException | Error failure) {
            model.close();
            throw failure;
        }
    }

    @Override
    public String providerId() {
        return model.providerId();
    }

    @Override
    public boolean requiresProtectedContent() {
        return false;
    }

    @Override
    public InspectionResult inspect(InspectionRequest request) {
        Set<String> inspectedSegmentIds = new HashSet<>();
        List<InspectionFinding> findings = new ArrayList<>();
        boolean acquired = false;
        try {
            acquired = lock.tryLock(request.remaining().toNanos(), TimeUnit.NANOSECONDS);
            if (!acquired) {
                throw new InspectionException(InspectionFailure.TIMEOUT);
            }
            if (closed) {
                throw new InspectionException(InspectionFailure.CONFIGURATION);
            }
            int chunks = 0;
            for (ContentSegment segment : request.segments()) {
                request.checkActive();
                List<Map<String, long[]>> windows = model.encode(segment.text(), request.limits().maxChunks() - chunks);
                if (windows.isEmpty() || windows.size() > request.limits().maxChunks() - chunks) {
                    throw new InspectionException(InspectionFailure.LIMIT_EXCEEDED);
                }
                chunks += windows.size();
                for (Map<String, long[]> window : windows) {
                    request.checkActive();
                    List<InspectionFinding> windowFindings = runtime.infer(window, segment.id(), model, request);
                    for (InspectionFinding finding : windowFindings) {
                        if (!segment.id().equals(finding.segmentId())) {
                            throw new InspectionException(InspectionFailure.MODEL_ERROR);
                        }
                        findings.add(finding);
                        if (findings.size() >= 10_000) {
                            throw new InspectionException(InspectionFailure.LIMIT_EXCEEDED);
                        }
                    }
                }
                inspectedSegmentIds.add(segment.id());
            }
            request.checkActive();
            return InspectionResult.completed(inspectedSegmentIds, findings);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InspectionException(InspectionFailure.CANCELLED);
        } catch (InspectionException ex) {
            if (ex.failure() == InspectionFailure.CANCELLED) {
                throw ex;
            }
            return InspectionResult.failed(ex.failure(), inspectedSegmentIds, findings);
        } catch (Exception ex) {
            InspectionRequest.checkInterrupted();
            return InspectionResult.failed(InspectionFailure.MODEL_ERROR, inspectedSegmentIds, findings);
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
