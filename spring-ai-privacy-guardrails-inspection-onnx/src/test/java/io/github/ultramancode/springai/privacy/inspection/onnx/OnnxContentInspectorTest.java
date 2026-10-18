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
import org.mockito.MockedConstruction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(60)
class OnnxContentInspectorTest {

    @TempDir Path tempPath;

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
    void laterInferenceFailureKeepsPriorFindingsAndCompletedSegments() {
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
            assertThat(partial.findings()).containsExactly(
                    new InspectionFinding("s1", TOXICITY, "INSULT", 0.99));
        }
    }

    @ParameterizedTest
    // The second threshold is Math.nextUp(0.5), the next representable double after 0.5.
    @CsvSource({"0.5, 1", "0.5000000000000001, 0"})
    void thresholdIncludesTheExactScore(double threshold, int expectedFindingCount) {
        OnnxClassificationConfig config = classificationBuilder()
                .logitCount(1)
                .labels(List.of(new OnnxClassificationConfig.Label(0, POLICY_VIOLATION, "BOUNDARY", threshold))).build();
        try (MockedConstruction<OnnxSequenceClassifier> ignored = mockConstruction(
                OnnxSequenceClassifier.class, (classifier, context) ->
                when(classifier.classify(any())).thenReturn(new double[] {0.5}));
                OnnxContentInspector inspector = new OnnxContentInspector("boundary",
                        OnnxInspectionConfig.defaults(OnnxTestModel.model()), config)) {
            InspectionResult result = inspector.inspect(request(SAFE));
            assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.findings()).hasSize(expectedFindingCount)
                    .allSatisfy(finding -> assertThat(finding.score()).isEqualTo(0.5));
        }
    }

    @ParameterizedTest
    // The pinned public model returns six class logits in the "logits" output.
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
        Path model = Files.writeString(tempPath.resolve("private-model.onnx"), "invalid ONNX");
        assertThat(OnnxInspectionConfig.defaults(model).toString()).doesNotContain(model.toString());
        assertThat(classification().toString()).doesNotContain(OnnxTestModel.directory().toString());
        assertThatThrownBy(() -> new OnnxContentInspector("local", OnnxInspectionConfig.defaults(model), classification()))
                .isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.CONFIGURATION))
                .hasMessageNotContaining("private-model").hasMessageNotContaining(tempPath.toString()).hasNoCause();
    }

    @Test
    void queueWaitUsesItsOwnDeadlineWhileAnotherInferenceRuns() throws Exception {
        CountDownLatch inferenceStarted = new CountDownLatch(1);
        CountDownLatch allowInferenceToFinish = new CountDownLatch(1);
        ExecutorService inspectionExecutor = Executors.newSingleThreadExecutor();
        try (MockedConstruction<OnnxSequenceClassifier> classifiers = mockConstruction(OnnxSequenceClassifier.class);
                OnnxContentInspector inspector = inspector()) {
            OnnxSequenceClassifier classifier = classifiers.constructed().get(0);
            when(classifier.classify(any())).thenAnswer(invocation -> {
                // Worker thread: signal inference entry, then wait while holding the inspector lock.
                inferenceStarted.countDown();
                assertThat(allowInferenceToFinish.await(10, TimeUnit.SECONDS))
                        .as("inference is allowed to finish").isTrue();
                return new double[6];
            });

            // Run the first request on the executor's worker thread.
            Future<InspectionResult> activeInspectionFuture =
                    inspectionExecutor.submit(() -> inspector.inspect(request(SAFE)));
            try {
                // Test thread: wait until the worker reaches classify() and holds the inspector lock.
                assertThat(inferenceStarted.await(5, TimeUnit.SECONDS)).isTrue();

                // This second request runs on the test thread and must time out waiting for the same lock.
                InspectionResult waitingInspectionResult = inspector.inspect(request(Duration.ofMillis(50), SAFE));
                assertThat(waitingInspectionResult.failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
                assertThat(waitingInspectionResult.completedSegmentIds()).isEmpty();
            } finally {
                // Let the worker finish even if an assertion fails.
                allowInferenceToFinish.countDown();
            }
            assertThat(activeInspectionFuture.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(InspectionResult.Status.COMPLETED);
        } finally {
            inspectionExecutor.shutdownNow();
        }
    }

    @Test
    void inspectionCompletesWhenLastInferenceFinishesAfterDeadline() {
        try (MockedConstruction<OnnxSequenceClassifier> classifiers = mockConstruction(OnnxSequenceClassifier.class);
                OnnxContentInspector inspector = inspector()) {
            InspectionRequest request = request(Duration.ofSeconds(1), INSULT);
            OnnxSequenceClassifier classifier = classifiers.constructed().get(0);
            when(classifier.classify(any())).thenAnswer(invocation -> {
                // Let the deadline expire while the last inference is running.
                TimeUnit.NANOSECONDS.sleep(request.remaining().toNanos());
                return new double[] {0, 0, 0, 0, 0.99, 0};
            });

            InspectionResult result = inspector.inspect(request);

            assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.completedSegmentIds()).containsExactly("s0");
            assertThat(result.findings()).containsExactly(new InspectionFinding("s0", TOXICITY, "INSULT", 0.99));
        }
    }

    @Test
    void interruptionDuringInferencePreservesPartialResults() {
        try (MockedConstruction<OnnxSequenceClassifier> classifiers = mockConstruction(OnnxSequenceClassifier.class);
                OnnxContentInspector inspector = inspector()) {
            InspectionRequest request = request(INSULT, INSULT);
            OnnxSequenceClassifier classifier = classifiers.constructed().get(0);
            double[] scores = {0, 0, 0, 0, 0.99, 0};
            // Complete the first segment, then interrupt during the second inference.
            when(classifier.classify(any())).thenReturn(scores).thenAnswer(invocation -> {
                Thread.currentThread().interrupt();
                return scores;
            });

            try {
                InspectionResult result = inspector.inspect(request);

                assertThat(result.status()).isEqualTo(InspectionResult.Status.FAILED);
                assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);
                assertThat(result.completedSegmentIds()).containsExactly("s0");
                assertThat(result.findings()).containsExactly(
                        new InspectionFinding("s0", TOXICITY, "INSULT", 0.99),
                        new InspectionFinding("s1", TOXICITY, "INSULT", 0.99));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                // Clear the interrupt status so it does not affect subsequent tests.
                Thread.interrupted();
            }
        }
    }

    @Test
    void timeoutBeforeTheNextWindowPreservesFindings() {
        try (MockedConstruction<OnnxSequenceClassifier> classifiers = mockConstruction(OnnxSequenceClassifier.class);
                OnnxContentInspector inspector = inspector()) {
            // Use a long segment so more windows remain after the first inference.
            String longText = INSULT.repeat(60);
            InspectionRequest request = request(Duration.ofSeconds(1), longText);
            OnnxSequenceClassifier classifier = classifiers.constructed().get(0);
            when(classifier.classify(any())).thenAnswer(invocation -> {
                TimeUnit.NANOSECONDS.sleep(request.remaining().toNanos());
                return new double[] {0, 0, 0, 0, 0.99, 0};
            });

            InspectionResult result = inspector.inspect(request);

            assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
            assertThat(result.completedSegmentIds()).isEmpty();
            assertThat(result.findings()).containsExactly(
                    new InspectionFinding("s0", TOXICITY, "INSULT", 0.99));
            // The next window must not reach the classifier after the deadline.
            verify(classifier).classify(any());
        }
    }

    @Test
    void concurrentRequestsKeepTheirResultsSeparate() throws Exception {
        ExecutorService inspectionExecutor = Executors.newFixedThreadPool(8);
        try (OnnxContentInspector inspector = inspector()) {
            List<Future<InspectionResult>> inspectionFutures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                String text = i % 2 == 0 ? SAFE : INSULT;
                inspectionFutures.add(inspectionExecutor.submit(() -> inspector.inspect(request(text))));
            }
            for (int i = 0; i < inspectionFutures.size(); i++) {
                int expectedFindingCount = i % 2;
                InspectionResult result = inspectionFutures.get(i).get(10, TimeUnit.SECONDS);
                assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
                assertThat(result.completedSegmentIds()).containsExactly("s0");
                assertThat(result.findings()).hasSize(expectedFindingCount);
            }
        } finally {
            inspectionExecutor.shutdownNow();
        }
    }
}
