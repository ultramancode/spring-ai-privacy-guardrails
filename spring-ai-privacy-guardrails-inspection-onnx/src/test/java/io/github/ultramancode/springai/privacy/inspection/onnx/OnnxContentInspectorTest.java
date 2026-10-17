package io.github.ultramancode.springai.privacy.inspection.onnx;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding.Category.POLICY_VIOLATION;
import static io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding.Category.TOXICITY;
import static io.github.ultramancode.springai.privacy.inspection.onnx.OnnxTestModel.INSULT;
import static io.github.ultramancode.springai.privacy.inspection.onnx.OnnxTestModel.LONG_TEXT_WITH_INSULT_AT_END;
import static io.github.ultramancode.springai.privacy.inspection.onnx.OnnxTestModel.SAFE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(60)
class OnnxContentInspectorTest {

    @TempDir Path temp;

    private Path graph(String profile) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/" + profile + ".onnx.base64")) {
            return Files.write(temp.resolve(profile + ".onnx"), Base64.getMimeDecoder().decode(input.readAllBytes()));
        }
    }

    private OnnxClassificationConfig.Builder classificationBuilder() {
        return OnnxTestModel.classificationBuilder(OnnxTestModel.directory().resolve("tokenizer.json"),
                OnnxTestModel.directory().resolve("tokenizer_config.json"));
    }

    private OnnxClassificationConfig classification() {
        return classificationBuilder().build();
    }

    private OnnxContentInspector inspector() {
        return new OnnxContentInspector("local", OnnxInspectionConfig.defaults(OnnxTestModel.model()), classification());
    }

    private InspectionRequest request(String... texts) {
        return request(Duration.ofSeconds(30), texts);
    }

    private InspectionRequest request(Duration timeout, String... texts) {
        List<ContentSegment> segments = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            segments.add(new ContentSegment("s" + i, ContentSegment.Role.USER,
                    ContentSegment.PrivacyProcessingStatus.UNKNOWN, texts[i]));
        }
        return new InspectionRequest(segments, new InspectionLimits(64, 131_072, timeout));
    }

    @Test
    void overlappingWindowsKeepTheMaximumScoreForEachSegment() {
        // Python reference scores from public-model-reference.json.
        double expectedLongTextScore = 0.9717001495402859;
        double expectedInsultScore = 0.9573231977246978;
        try (OnnxContentInspector inspector = inspector()) {
            InspectionResult result = inspector.inspect(request(LONG_TEXT_WITH_INSULT_AT_END, INSULT));
            assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.completedSegmentIds()).containsExactlyInAnyOrder("s0", "s1");
            assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("s0", "s1");
            assertThat(result.findings()).allSatisfy(finding -> {
                assertThat(finding.category()).isEqualTo(TOXICITY);
                assertThat(finding.code()).isEqualTo("INSULT");
            });
            assertThat(result.findings().get(0).score())
                    .isCloseTo(expectedLongTextScore, within(0.0001));
            assertThat(result.findings().get(1).score())
                    .isCloseTo(expectedInsultScore, within(0.0001));
        }
    }

    @Test
    void sameOutputIndexSupportsIndependentThresholds() {
        OnnxClassificationConfig config = classificationBuilder().labels(List.of(
                new OnnxClassificationConfig.Label(4, TOXICITY, "CAUTION", 0.9),
                new OnnxClassificationConfig.Label(4, TOXICITY, "HIGH_SCORE", 0.965))).build();
        try (OnnxContentInspector inspector = new OnnxContentInspector(
                "local", OnnxInspectionConfig.defaults(OnnxTestModel.model()), config)) {
            assertThat(inspector.inspect(request(INSULT)).findings()).extracting(InspectionFinding::code)
                    .containsExactly("CAUTION");
            assertThat(inspector.inspect(request(LONG_TEXT_WITH_INSULT_AT_END)).findings()).extracting(InspectionFinding::code)
                    .containsExactly("CAUTION", "HIGH_SCORE");
        }
    }

    @Test
    void exceedingWindowLimitPreservesPartialEvidenceAndCompletedSegments() {
        try (OnnxContentInspector inspector = new OnnxContentInspector("local",
                new OnnxInspectionConfig(OnnxTestModel.model(), 2, 2), classification())) {
            InspectionResult result = inspector.inspect(request(SAFE, INSULT.repeat(60)));
            assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
            assertThat(result.completedSegmentIds()).containsExactly("s0");
            assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("s1");
        }
    }

    @Test
    void laterPredictionFailureKeepsPriorFindingsAndCompletedSegments() {
        try (MockedConstruction<OnnxSequenceClassifier> ignored = mockConstruction(
                OnnxSequenceClassifier.class, (classifier, context) ->
                when(classifier.classify(any()))
                        .thenReturn(new double[] {0, 0, 0, 0, 0, 0})
                        .thenReturn(new double[] {0, 0, 0, 0, 0.99, 0})
                        .thenThrow(new InspectionException(InspectionFailureCode.MODEL_ERROR)));
                OnnxContentInspector inspector = inspector()) {
            InspectionResult partial = inspector.inspect(request(SAFE, INSULT.repeat(60)));
            assertThat(partial.failureCode()).isEqualTo(InspectionFailureCode.MODEL_ERROR);
            assertThat(partial.completedSegmentIds()).containsExactly("s0");
            assertThat(partial.findings()).extracting(InspectionFinding::segmentId).containsExactly("s1");
        }
    }

    @Test
    void nonfiniteNativeOutputFailsWithoutFindings() throws Exception {
        try (OnnxContentInspector inspector = new OnnxContentInspector("invalid",
                OnnxInspectionConfig.defaults(graph("nonfinite")), classification())) {
            InspectionResult result = inspector.inspect(request(SAFE));
            assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.MODEL_ERROR);
            assertThat(result.completedSegmentIds()).isEmpty();
            assertThat(result.findings()).isEmpty();
        }
    }

    @ParameterizedTest
    @CsvSource({"0.5, 1", "0.5000000000000001, 0"})
    void thresholdIncludesTheExactScore(double threshold, int expectedFindingCount) throws Exception {
        OnnxClassificationConfig config = classificationBuilder().logitCount(1)
                .labels(List.of(new OnnxClassificationConfig.Label(0, POLICY_VIOLATION, "BOUNDARY", threshold))).build();
        try (OnnxContentInspector inspector = new OnnxContentInspector("boundary",
                OnnxInspectionConfig.defaults(graph("single")), config)) {
            // A zero native logit produces exactly 0.5 after SIGMOID.
            InspectionResult result = inspector.inspect(request(SAFE));
            assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.findings()).hasSize(expectedFindingCount)
                    .allSatisfy(finding -> assertThat(finding.score()).isEqualTo(0.5));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"input-float", "unknown-input", "wrong-length", "int32", "rank-one",
            "output-double", "output-rank-one", "missing-ids", "batch-two"})
    void incompatibleModelMetadataFailsBeforeAnyInspection(String profile) throws Exception {
        Path model = graph(profile);
        assertThatThrownBy(() -> new OnnxContentInspector("local", OnnxInspectionConfig.defaults(model), classification()))
                .isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CONFIGURATION))
                .hasMessageNotContaining(temp.toString()).hasNoCause();
    }

    @ParameterizedTest
    @CsvSource({"5, logits", "6, missing"})
    void publicModelRejectsIncorrectOutputConfiguration(int logitCount, String modelOutputName) {
        OnnxClassificationConfig config = classificationBuilder()
                .logitCount(logitCount).modelOutputName(modelOutputName).build();
        assertThatThrownBy(() -> new OnnxContentInspector("local",
                OnnxInspectionConfig.defaults(OnnxTestModel.model()), config))
                .isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CONFIGURATION))
                .hasNoCause();
    }

    @Test
    void int32InputsRequireTheMatchingConfiguredType() throws Exception {
        OnnxClassificationConfig config = classificationBuilder()
                .tokenInputType(OnnxClassificationConfig.TokenInputType.INT32).build();
        try (OnnxContentInspector inspector = new OnnxContentInspector(
                "int32", OnnxInspectionConfig.defaults(graph("int32")), config)) {
            assertThat(inspector.inspect(request(SAFE)).status()).isEqualTo(InspectionResult.Status.COMPLETED);
        }
    }

    @Test
    void selectedOutputCanFollowAnUnrelatedOutputAndUseAnotherName() throws Exception {
        OnnxClassificationConfig config = classificationBuilder().modelOutputName("scores").build();
        try (OnnxContentInspector inspector = new OnnxContentInspector("selected",
                OnnxInspectionConfig.defaults(graph("output-selection")), config)) {
            assertThat(inspector.inspect(request(SAFE)).findings()).singleElement()
                    .satisfies(finding -> assertThat(finding.score()).isCloseTo(1 / (1 + Math.exp(-3)), within(1e-12)));
        }
    }

    @ParameterizedTest
    @CsvSource({"5, FAILED", "6, COMPLETED"})
    void dynamicOutputLogitCountIsCheckedAfterInference(int logitCount, InspectionResult.Status status) throws Exception {
        OnnxClassificationConfig config = classificationBuilder().logitCount(logitCount).build();
        try (OnnxContentInspector inspector = new OnnxContentInspector("dynamic",
                OnnxInspectionConfig.defaults(graph("dynamic-wrong")), config)) {
            InspectionResult result = inspector.inspect(request(SAFE));
            assertThat(result.status()).isEqualTo(status);
            assertThat(result.failureCode()).isEqualTo(
                    status == InspectionResult.Status.FAILED ? InspectionFailureCode.MODEL_ERROR : null);
        }
    }

    @Test
    void closingOneInspectorIsIdempotentAndDoesNotAffectOthers() {
        try (OnnxContentInspector other = inspector()) {
            OnnxContentInspector first = inspector();
            first.close();
            first.close();
            assertThat(first.inspect(request(SAFE)).failureCode()).isEqualTo(InspectionFailureCode.INSPECTOR_CLOSED);
            assertThat(other.inspect(request(SAFE)).status()).isEqualTo(InspectionResult.Status.COMPLETED);
        }
    }

    @Test
    void modelLoadingFailuresAndSettingsDoNotExposePaths() throws Exception {
        Path model = Files.writeString(temp.resolve("private-model.onnx"), "invalid ONNX");
        assertThat(OnnxInspectionConfig.defaults(model).toString()).doesNotContain(model.toString());
        assertThat(classification().toString()).doesNotContain(OnnxTestModel.directory().toString());
        assertThatThrownBy(() -> new OnnxContentInspector("local", OnnxInspectionConfig.defaults(model), classification()))
                .isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CONFIGURATION))
                .hasMessageNotContaining("private-model").hasMessageNotContaining(temp.toString()).hasNoCause();
    }

    @Test
    void queueWaitUsesItsOwnDeadlineWhileAnotherPredictionRuns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (MockedConstruction<OnnxSequenceClassifier> ignored = mockConstruction(
                OnnxSequenceClassifier.class, (classifier, context) ->
                when(classifier.classify(any())).thenAnswer(invocation -> {
                    entered.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("Prediction was not released");
                    }
                    return new double[] {0, 0, 0, 0, 0.99, 0};
                }));
                OnnxContentInspector inspector = inspector()) {
            Future<InspectionResult> active = pool.submit(() -> inspector.inspect(request(INSULT)));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                InspectionResult queued = inspector.inspect(request(Duration.ofMillis(50), "hello"));
                assertThat(queued.failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
                assertThat(queued.completedSegmentIds()).isEmpty();
            } finally {
                release.countDown();
            }
            assertThat(active.get(5, TimeUnit.SECONDS).status()).isEqualTo(InspectionResult.Status.COMPLETED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedInferenceRetainsFindingsAfterDeadlineExpiryOrInterruption(boolean interrupt) {
        AtomicReference<InspectionRequest> activeRequest = new AtomicReference<>();
        try (MockedConstruction<OnnxSequenceClassifier> ignored = mockConstruction(
                OnnxSequenceClassifier.class, (classifier, context) ->
                when(classifier.classify(any())).thenReturn(new double[] {0, 0, 0, 0, 0.99, 0}).thenAnswer(invocation -> {
                    if (interrupt) {
                        Thread.currentThread().interrupt();
                    } else {
                        TimeUnit.NANOSECONDS.sleep(activeRequest.get().remaining().toNanos());
                    }
                    return new double[] {0, 0, 0, 0, 0.99, 0};
                }));
                OnnxContentInspector inspector = inspector()) {
            InspectionRequest request = request(Duration.ofSeconds(1), INSULT, INSULT);
            activeRequest.set(request);
            try {
                InspectionResult result = inspector.inspect(request);
                if (interrupt) {
                    assertThat(result.status()).isEqualTo(InspectionResult.Status.FAILED);
                    assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);
                    assertThat(result.completedSegmentIds()).containsExactly("s0");
                } else {
                    assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
                    assertThat(result.completedSegmentIds()).containsExactlyInAnyOrder("s0", "s1");
                }
                assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("s0", "s1");
                assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupt);
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void timeoutBeforeTheNextWindowPreservesFindings() {
        AtomicReference<InspectionRequest> activeRequest = new AtomicReference<>();
        try (MockedConstruction<OnnxSequenceClassifier> classifiers = mockConstruction(
                OnnxSequenceClassifier.class, (classifier, context) ->
                when(classifier.classify(any())).thenAnswer(invocation -> {
                    TimeUnit.NANOSECONDS.sleep(activeRequest.get().remaining().toNanos());
                    return new double[] {0, 0, 0, 0, 0.99, 0};
                }));
                OnnxContentInspector inspector = inspector()) {
            InspectionRequest request = request(Duration.ofSeconds(1), INSULT.repeat(60));
            activeRequest.set(request);

            InspectionResult result = inspector.inspect(request);

            assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
            assertThat(result.completedSegmentIds()).isEmpty();
            assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("s0");
            verify(classifiers.constructed().get(0), times(1)).classify(any());
        }
    }

    @Test
    void concurrentRequestsKeepTheirResultsSeparate() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try (OnnxContentInspector inspector = inspector()) {
            List<Future<InspectionResult>> pending = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String text = i % 2 == 0 ? SAFE : INSULT;
                pending.add(pool.submit(() -> inspector.inspect(request(text))));
            }
            for (int i = 0; i < pending.size(); i++) {
                InspectionResult result = pending.get(i).get(10, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
                assertThat(result.completedSegmentIds()).containsExactly("s0");
                assertThat(result.findings()).hasSize(i % 2);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
