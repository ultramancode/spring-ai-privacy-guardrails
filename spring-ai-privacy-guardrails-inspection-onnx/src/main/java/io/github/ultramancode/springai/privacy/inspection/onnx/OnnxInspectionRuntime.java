package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtLoggingLevel;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Owns native inference resources and deadline termination; knows no model labels or window limit. */
final class OnnxInspectionRuntime implements AutoCloseable {
    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        Thread thread = new Thread(task, "inspection-onnx-deadline");
                        thread.setDaemon(true);
                        return thread;
                    });
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final Map<String, NodeInfo> inputInfo;

    OnnxInspectionRuntime(OnnxInspectionConfig config, OnnxInspectionModel model) {
        if (!Files.isRegularFile(config.model())) {
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
        OrtSession loaded = null;
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(config.intraOpThreads());
            options.setInterOpNumThreads(1);
            options.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_FATAL);
            loaded = environment.createSession(config.model().toAbsolutePath().toString(), options);
            Map<String, NodeInfo> inputs = loaded.getInputInfo();
            for (NodeInfo node : inputs.values()) {
                if (!(node.getInfo() instanceof TensorInfo tensor)
                        || (tensor.type != OnnxJavaType.INT64 && tensor.type != OnnxJavaType.INT32)
                        || tensor.getShape().length != 2 || tensor.getShape()[0] > 1) {
                    throw new InspectionException(InspectionFailureCode.CONFIGURATION);
                }
            }
            if (inputs.isEmpty()) {
                throw new InspectionException(InspectionFailureCode.CONFIGURATION);
            }
            model.validate(Map.copyOf(inputs), Map.copyOf(loaded.getOutputInfo()));
            this.inputInfo = Map.copyOf(inputs);
            this.session = loaded;
        } catch (Exception ex) {
            if (loaded != null) {
                try {
                    loaded.close();
                } catch (OrtException ignored) {
                }
            }
            throw new InspectionException(InspectionFailureCode.CONFIGURATION);
        }
    }

    List<InspectionFinding> infer(Map<String, long[]> window, String segmentId,
            OnnxInspectionModel model, InspectionRequest request) throws OrtException {
        if (!window.keySet().equals(inputInfo.keySet())) {
            throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
        }
        long deadline = System.nanoTime() + request.remaining().toNanos();
        Map<String, OnnxTensor> inputs = new HashMap<>();
        OrtSession.RunOptions runOptions = new OrtSession.RunOptions();
        AtomicBoolean runOptionsClosed = new AtomicBoolean();
        AtomicReference<InspectionFailureCode> terminationReason = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        ScheduledFuture<?> watchdog =
                WATCHDOG.scheduleAtFixedRate(
                        () -> {
                            InspectionFailureCode failure;
                            if (caller.isInterrupted()) {
                                failure = InspectionFailureCode.CANCELLED;
                            } else if (System.nanoTime() - deadline >= 0) {
                                failure = InspectionFailureCode.TIMEOUT;
                            } else {
                                return;
                            }
                            synchronized (runOptions) {
                                if (runOptionsClosed.get()) {
                                    return;
                                }
                                terminationReason.set(failure);
                                try {
                                    runOptions.setTerminate(true);
                                } catch (OrtException ignored) {
                                }
                            }
                        },
                        0,
                        5,
                        TimeUnit.MILLISECONDS);
        try {
            for (Map.Entry<String, long[]> entry : window.entrySet()) {
                TensorInfo info = (TensorInfo) inputInfo.get(entry.getKey()).getInfo();
                long[] values = entry.getValue();
                long[] shape = info.getShape();
                if (values == null || values.length == 0 || (shape[1] > 0 && shape[1] != values.length)) {
                    throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
                }
                OnnxTensor tensor;
                if (info.type == OnnxJavaType.INT64) {
                    tensor = OnnxTensor.createTensor(environment, new long[][] {values});
                } else {
                    int[] narrowed = new int[values.length];
                    for (int i = 0; i < values.length; i++) {
                        narrowed[i] = Math.toIntExact(values[i]);
                    }
                    tensor = OnnxTensor.createTensor(environment, new int[][] {narrowed});
                }
                inputs.put(entry.getKey(), tensor);
            }
            try (OrtSession.Result outputs = session.run(inputs, runOptions)) {
                request.checkActive();
                return List.copyOf(model.decode(segmentId, outputs));
            }
        } catch (OrtException ex) {
            if (terminationReason.get() != null) {
                throw new InspectionException(terminationReason.get());
            }
            throw ex;
        } finally {
            watchdog.cancel(false);
            try {
                synchronized (runOptions) {
                    runOptionsClosed.set(true);
                    runOptions.close();
                }
            } finally {
                inputs.values().forEach(OnnxTensor::close);
            }
        }
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException ex) {
            throw new InspectionException(InspectionFailureCode.MODEL_ERROR);
        }
    }
}
