package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import io.github.ultramancode.springai.privacy.inspection.core.ContentInspector;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailure;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CPU ONNX Prompt Guard 2 binary classifier (BENIGN=0, MALICIOUS=1).
 * Owns the session/tokenizer, not the process-wide OrtEnvironment. Close when no longer used.
 * Tokenizer overflowing encodings preserve special tokens in every overlapping 512-token window.
 */
public final class PromptGuardContentInspector implements ContentInspector, AutoCloseable {

    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        Thread thread = new Thread(task, "inspection-onnx-deadline");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final double threshold;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean closed;

    public PromptGuardContentInspector(PromptGuardConfig config) {
        Objects.requireNonNull(config, "config");
        if (!Files.isRegularFile(config.model()) || !Files.isRegularFile(config.tokenizer())) {
            throw new InspectionException(InspectionFailure.CONFIGURATION);
        }
        this.threshold = config.threshold();
        this.environment = OrtEnvironment.getEnvironment();
        HuggingFaceTokenizer loadedTokenizer = null;
        OrtSession loadedSession = null;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(config.intraOpThreads());
            options.setInterOpNumThreads(1);
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL);
            loadedTokenizer =
                    HuggingFaceTokenizer.newInstance(
                            config.tokenizer(),
                            Map.of(
                                    "maxLength",
                                    "512",
                                    "modelMaxLength",
                                    "512",
                                    "padding",
                                    "max_length",
                                    "truncation",
                                    "true",
                                    "stride",
                                    Integer.toString(config.overlapTokens()),
                                    "withOverflowingTokens",
                                    "true",
                                    "addSpecialTokens",
                                    "true"));
            loadedSession =
                    environment.createSession(config.model().toAbsolutePath().toString(), options);
            Set<String> inputs = loadedSession.getInputNames();
            if (!inputs.containsAll(Set.of("input_ids", "attention_mask"))
                    || !Set.of("input_ids", "attention_mask", "token_type_ids").containsAll(inputs)
                    || !loadedSession.getOutputNames().contains("logits")) {
                throw new InspectionException(InspectionFailure.CONFIGURATION);
            }
            for (NodeInfo node : loadedSession.getInputInfo().values()) {
                if (!(node.getInfo() instanceof TensorInfo tensor)
                        || tensor.type != OnnxJavaType.INT64
                        || tensor.getShape().length != 2) {
                    throw new InspectionException(InspectionFailure.CONFIGURATION);
                }
            }
            this.tokenizer = loadedTokenizer;
            this.session = loadedSession;
        } catch (Exception ex) {
            if (loadedTokenizer != null) {
                loadedTokenizer.close();
            }
            if (loadedSession != null) {
                try {
                    loadedSession.close();
                } catch (OrtException ignored) {
                }
            }
            throw new InspectionException(InspectionFailure.CONFIGURATION);
        }
    }

    @Override
    public String providerId() {
        return "prompt-guard";
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
                Encoding first = tokenizer.encode(segment.text());
                List<Encoding> encodings = new ArrayList<>();
                encodings.add(first);
                encodings.addAll(List.of(first.getOverflowing()));
                if (encodings.size() > request.limits().maxChunks() - chunks) {
                    throw new InspectionException(InspectionFailure.LIMIT_EXCEEDED);
                }
                chunks += encodings.size();
                for (Encoding encoding : encodings) {
                    request.checkActive();
                    double score = infer(encoding, request);
                    // Preserve evidence immediately, including if a later window fails.
                    if (score >= threshold) {
                        findings.add(
                                new InspectionFinding(
                                        segment.id(),
                                        InspectionFinding.Category.PROMPT_ATTACK,
                                        "MALICIOUS",
                                        score));
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

    private double infer(Encoding encoding, InspectionRequest request) throws OrtException {
        if (encoding.getIds().length == 0 || encoding.getIds().length > 512) {
            throw new InspectionException(InspectionFailure.MODEL_ERROR);
        }
        long deadline = System.nanoTime() + request.remaining().toNanos();
        Map<String, OnnxTensor> inputs = new HashMap<>();
        OrtSession.RunOptions run = new OrtSession.RunOptions();
        AtomicBoolean runClosed = new AtomicBoolean();
        AtomicReference<InspectionFailure> stopped = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        ScheduledFuture<?> watchdog =
                WATCHDOG.scheduleAtFixedRate(
                        () -> {
                            InspectionFailure failure =
                                    caller.isInterrupted()
                                            ? InspectionFailure.CANCELLED
                                            : System.nanoTime() - deadline >= 0
                                                    ? InspectionFailure.TIMEOUT
                                                    : null;
                            if (failure != null) {
                                synchronized (run) {
                                    if (!runClosed.get()) {
                                        stopped.set(failure);
                                        try {
                                            run.setTerminate(true);
                                        } catch (OrtException ignored) {
                                        }
                                    }
                                }
                            }
                        },
                        0,
                        5,
                        TimeUnit.MILLISECONDS);
        try {
            inputs.put(
                    "input_ids",
                    OnnxTensor.createTensor(environment, new long[][] {encoding.getIds()}));
            inputs.put(
                    "attention_mask",
                    OnnxTensor.createTensor(
                            environment, new long[][] {encoding.getAttentionMask()}));
            if (session.getInputNames().contains("token_type_ids")) {
                inputs.put(
                        "token_type_ids",
                        OnnxTensor.createTensor(environment, new long[][] {encoding.getTypeIds()}));
            }
            try (OrtSession.Result outputs = session.run(inputs, run)) {
                request.checkActive();
                return readMaliciousProbability(outputs);
            }
        } catch (OrtException ex) {
            if (stopped.get() != null) {
                throw new InspectionException(stopped.get());
            }
            throw ex;
        } finally {
            watchdog.cancel(false);
            synchronized (run) {
                runClosed.set(true);
                run.close();
            }
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    private static double readMaliciousProbability(OrtSession.Result outputs) throws OrtException {
        Object value = outputs.get("logits")
                .orElseThrow(() -> new InspectionException(InspectionFailure.MODEL_ERROR))
                .getValue();
        if (!(value instanceof float[][] logits)
                || logits.length != 1
                || logits[0].length != 2
                || !Float.isFinite(logits[0][0])
                || !Float.isFinite(logits[0][1])) {
            throw new InspectionException(InspectionFailure.MODEL_ERROR);
        }
        double benignLogit = logits[0][0];
        double maliciousLogit = logits[0][1];
        return 1.0 / (1.0 + Math.exp(benignLogit - maliciousLogit));
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (!closed) {
                closed = true;
                tokenizer.close();
                try {
                    session.close();
                } catch (OrtException ex) {
                    throw new InspectionException(InspectionFailure.MODEL_ERROR);
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
