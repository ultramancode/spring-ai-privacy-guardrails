package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionDecision;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionPolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.junit.jupiter.api.BeforeEach;
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

import static io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding.Category.*;
import static io.github.ultramancode.springai.privacy.inspection.onnx.OnnxClassificationConfig.Activation.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@Timeout(60)
class OnnxContentInspectorTest {

    @TempDir Path temp;
    private Path tokenizer;
    private Path tokenizerConfig;

    @BeforeEach
    void prepareTokenizer() throws Exception {
        tokenizer = copy("tokenizer.json");
        tokenizerConfig = copy("tokenizer_config.json");
    }

    private Path copy(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/" + resource)) {
            return Files.write(temp.resolve(resource), input.readAllBytes());
        }
    }

    private Path graph(String profile) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/" + profile + ".onnx.base64")) {
            return Files.write(temp.resolve(profile + ".onnx"),
                    Base64.getMimeDecoder().decode(input.readAllBytes()));
        }
    }

    private OnnxClassificationConfig classification() {
        return OnnxClassificationConfig.promptGuard2(tokenizer, tokenizerConfig, 0.5);
    }

    private OnnxContentInspector inspector(String profile) throws Exception {
        OnnxClassificationConfig classification = classification();
        if (profile.equals("int32")) {
            classification = new OnnxClassificationConfig(tokenizer, tokenizerConfig, 512, 64, 2,
                    SOFTMAX, classification.labels(), OnnxClassificationConfig.TokenInputType.INT32);
        }
        return new OnnxContentInspector("local", OnnxInspectionConfig.defaults(graph(profile)), classification);
    }

    @Test
    void unsafeContentWindowFailsAtInspectorConstruction() throws Exception {
        var profile = classification();
        var config = new OnnxClassificationConfig(tokenizer, tokenizerConfig, 4, 2,
                profile.classCount(), profile.activation(), profile.labels());
        OnnxInspectionConfig inspectionConfig = OnnxInspectionConfig.defaults(graph("binary"));
        assertThatThrownBy(() -> {
            try (var ignored = new OnnxContentInspector("local", inspectionConfig, config)) {
                // No request is needed to reject the unsafe combination.
            }
        }).isInstanceOfSatisfying(InspectionException.class,
                failure -> assertThat(failure.failure()).isEqualTo(InspectionFailureCode.CONFIGURATION))
                .hasNoCause();
    }

    private InspectionRequest request(String... texts) {
        return request(Duration.ofSeconds(10), 10_000, texts);
    }

    private InspectionRequest request(Duration timeout, int maxFindings, String... texts) {
        List<ContentSegment> segments = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            segments.add(new ContentSegment("s" + i, ContentSegment.Role.USER,
                    ContentSegment.PrivacyProcessingStatus.UNKNOWN, texts[i]));
        }
        return new InspectionRequest(segments, new InspectionLimits(64, 131_072, maxFindings, timeout));
    }

    @ParameterizedTest
    @ValueSource(strings = {"binary", "int32", "ids-only", "fixed"})
    void djlRunsNamedTokenInputsWithTheConfiguredType(String profile) throws Exception {
        try (OnnxContentInspector inspector = inspector(profile)) {
            assertThat(inspector.requiresPrivacyProcessedContent()).isFalse();
            InspectionResult safe = inspector.inspect(request("hello world"));
            assertThat(safe.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(safe.completedSegmentIds()).containsExactly("s0");
            assertThat(safe.findings()).isEmpty();
            InspectionResult attack = inspector.inspect(request("hello attack"));
            assertThat(attack.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(attack.findings()).singleElement().satisfies(finding -> {
                assertThat(finding.code()).isEqualTo("MALICIOUS");
                assertThat(finding.category()).isEqualTo(PROMPT_ATTACK);
                assertThat(finding.score()).isCloseTo(1 / (1 + Math.exp(-7)), within(1e-12));
            });
        }
    }

    @Test
    void huggingFaceUnlimitedLengthSentinelUsesTheExplicitWindowAndKeepsPadding() throws Exception {
        Files.writeString(tokenizerConfig, """
                {"model_max_length":1000000000000000019884624838656,"pad_token":"[PAD]",
                 "unk_token":"[UNK]","cls_token":"[CLS]","sep_token":"[SEP]"}
                """);
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            Encoding encoded = windowTokenizer.encodeWindows("hello").get(0);
            assertThat(encoded.getIds()).hasSize(512).startsWith(2, 4, 3).endsWith(0);
            assertThat(encoded.getAttentionMask()).startsWith(1, 1, 1, 0).endsWith(0);
        }
        try (OnnxContentInspector inspector = inspector("binary")) {
            assertThat(inspector.inspect(request("attack")).findings()).hasSize(1);
        }
    }

    @Test
    void everyWindowIncludesSpecialTokensAndCoversTheTail() throws Exception {
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(classification())) {
            List<Encoding> windows = windowTokenizer.encodeWindows("hello ".repeat(1100) + "attack");
            assertThat(windows).hasSize(3);
            assertThat(windows.get(0).getIds()).startsWith(2, 4).endsWith(3);
            for (Encoding window : windows) {
                assertThat(window.getIds()).hasSize(512).startsWith(2);
            }
            assertThat(windows.get(2).getIds()).contains(20);
        }
        try (OnnxContentInspector inspector = inspector("binary")) {
            InspectionResult result = inspector.inspect(request("hello ".repeat(1100) + "attack"));
            assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.findings()).hasSize(1);
            assertThat(result.completedSegmentIds()).containsExactly("s0");
        }
    }

    @Test
    void overlappingWindowsKeepOneMaximumScorePerLabelAndSegment() throws Exception {
        try (OnnxContentInspector inspector = inspector("binary")) {
            InspectionResult result = inspector.inspect(request("word13 ".repeat(700) + "attack", "attack"));
            assertThat(result.findings()).hasSize(2);
            assertThat(result.findings()).allSatisfy(finding ->
                    assertThat(finding.score()).isGreaterThan(0.99));
            assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("s0", "s1");
        }
    }

    @Test
    void sameOutputIndexSupportsIndependentThresholdsAndKeepsEachCodesMaximumScore() throws Exception {
        OnnxClassificationConfig config = new OnnxClassificationConfig(tokenizer, tokenizerConfig,
                512, 64, 2, SOFTMAX, List.of(
                        new OnnxClassificationConfig.Label(1, PROMPT_ATTACK, "CAUTION", 0.6),
                        new OnnxClassificationConfig.Label(1, PROMPT_ATTACK, "HIGH_RISK", 0.9)));
        try (OnnxContentInspector inspector = new OnnxContentInspector("local",
                OnnxInspectionConfig.defaults(graph("binary")), config)) {
            InspectionResult moderate = inspector.inspect(request("word14"));
            assertThat(moderate.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(moderate.findings()).extracting(InspectionFinding::code).containsExactly("CAUTION");

            InspectionResult high = inspector.inspect(request("word14 ".repeat(700) + "attack"));
            assertThat(high.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(high.completedSegmentIds()).containsExactly("s0");
            assertThat(high.findings()).extracting(InspectionFinding::code).containsExactly("CAUTION", "HIGH_RISK");
            assertThat(high.findings()).allSatisfy(finding ->
                    assertThat(finding.score()).isCloseTo(1 / (1 + Math.exp(-7)), within(1e-12)));
        }
    }

    @Test
    void exceedingWindowsPreservesPartialEvidenceAndCompletedSegments() throws Exception {
        try (OnnxContentInspector inspector = new OnnxContentInspector("local",
                new OnnxInspectionConfig(graph("binary"), 2, 2), classification())) {
            InspectionResult result = inspector.inspect(request("hello", "attack ".repeat(700)));
            assertThat(result.failure()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
            assertThat(result.completedSegmentIds()).containsExactly("s0");
            assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("s1");
            InspectionService service = new InspectionService(List.of(inspector),
                    (id, findings) -> InspectionDecision.ALLOW, InspectionFailurePolicy.FAIL_OPEN);
            assertThatThrownBy(() -> service.inspect(request("attack ".repeat(1100))))
                    .isInstanceOfSatisfying(InspectionException.class, failure ->
                            assertThat(failure.failure()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED));
        }
    }

    @Test
    void findingBudgetAllowsTheExactLimitAndPreservesTheBoundedPrefix() throws Exception {
        try (OnnxContentInspector inspector = inspector("binary")) {
            InspectionResult exact = inspector.inspect(request(Duration.ofSeconds(10), 1, "attack ".repeat(1100)));
            assertThat(exact.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(exact.findings()).hasSize(1);
            InspectionResult overflow = inspector.inspect(request(Duration.ofSeconds(10), 1, "attack", "attack"));
            assertThat(overflow.failure()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
            assertThat(overflow.completedSegmentIds()).containsExactly("s0");
            assertThat(overflow.findings()).hasSize(1);
        }
    }

    @Test
    void nonfiniteLaterWindowKeepsPriorEvidenceAndCannotFailOpenPastAContentBlock() throws Exception {
        try (OnnxContentInspector inspector = inspector("nonfinite")) {
            InspectionResult partial = inspector.inspect(request("hello", "attack ".repeat(700) + "fault"));
            assertThat(partial.failure()).isEqualTo(InspectionFailureCode.MODEL_ERROR);
            assertThat(partial.completedSegmentIds()).containsExactly("s0");
            assertThat(partial.findings()).extracting(InspectionFinding::segmentId).containsExactly("s1");
            InspectionService service = new InspectionService(List.of(inspector),
                    InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);
            assertThat(service.inspect(request("attack ".repeat(700) + "fault")).decision())
                    .isEqualTo(InspectionDecision.BLOCK);
            assertThat(service.inspect(request("fault")).allowedAfterFailure()).isTrue();
        }
    }

    @Test
    void sigmoidUsesIndependentLabelThresholdsAndSupportsOneLogit() throws Exception {
        OnnxClassificationConfig multilabel = new OnnxClassificationConfig(tokenizer, tokenizerConfig,
                1024, 64, 3, SIGMOID, List.of(
                        new OnnxClassificationConfig.Label(0, POLICY_VIOLATION, "A", 0.9),
                        new OnnxClassificationConfig.Label(1, PROMPT_INJECTION, "B", 0.99),
                        new OnnxClassificationConfig.Label(2, PROMPT_LEAKING, "C", 0.5)));
        try (OnnxContentInspector inspector = new OnnxContentInspector("multi",
                OnnxInspectionConfig.defaults(graph("multilabel")), multilabel)) {
            assertThat(inspector.inspect(request("attack")).findings())
                    .extracting(InspectionFinding::code).containsExactly("A", "B");
        }
        OnnxClassificationConfig single = new OnnxClassificationConfig(tokenizer, tokenizerConfig,
                512, 64, 1, SIGMOID, List.of(new OnnxClassificationConfig.Label(0, PROMPT_ATTACK, "A", 0.5)));
        try (OnnxContentInspector inspector = new OnnxContentInspector("single",
                OnnxInspectionConfig.defaults(graph("single")), single)) {
            assertThat(inspector.inspect(request("attack")).findings()).hasSize(1);
            assertThat(inspector.inspect(request("hello")).findings()).isEmpty();
        }
    }

    @Test
    void nonzeroPadIdAndConfiguredWindowLengthComeFromMatchingTokenizerArtifacts() throws Exception {
        String json = Files.readString(tokenizer).replace("\"id\": 0", "\"id\": 7")
                .replace("\"[PAD]\": 0", "\"[PAD]\": 7").replace("\"word7\": 7", "\"word7\": 0");
        Files.writeString(tokenizer, json);
        OnnxClassificationConfig longWindow = new OnnxClassificationConfig(tokenizer, tokenizerConfig,
                2048, 64, 2, SOFTMAX, classification().labels());
        try (TokenWindowTokenizer windowTokenizer = new TokenWindowTokenizer(longWindow)) {
            Encoding encoded = windowTokenizer.encodeWindows("hello").get(0);
            assertThat(encoded.getIds()).hasSize(2048).startsWith(2, 4, 3).endsWith(7, 7);
            assertThat(encoded.getAttentionMask()).startsWith(1, 1, 1, 0).endsWith(0);
        }
        try (OnnxContentInspector inspector = new OnnxContentInspector("long",
                OnnxInspectionConfig.defaults(graph("binary")), longWindow)) {
            assertThat(inspector.inspect(request("hello ".repeat(2500) + "attack")).findings()).hasSize(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "hello"})
    void emptyAndBlankSegmentsStillHaveCompletedCoverage(String text) throws Exception {
        try (OnnxContentInspector inspector = inspector("binary")) {
            InspectionResult result = inspector.inspect(request(text));
            assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.completedSegmentIds()).containsExactly("s0");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"input-float", "unknown-input", "wrong-length",
            "rank-one", "wrong-output", "multilabel", "dynamic-wrong"})
    void incompatibleGraphsReportModelErrorDuringInference(String profile) throws Exception {
        try (OnnxContentInspector inspector = inspector(profile)) {
            InspectionResult result = inspector.inspect(request("hello"));
            assertThat(result.failure()).isEqualTo(InspectionFailureCode.MODEL_ERROR);
            assertThat(result.completedSegmentIds()).isEmpty();
            assertThat(result.findings()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"batch-two", "zero-batch"})
    void backendGraphLoadingFailuresHaveSanitizedDiagnostics(String profile) throws Exception {
        Path model = graph(profile);
        assertThatThrownBy(() -> new OnnxContentInspector("local", OnnxInspectionConfig.defaults(model), classification()))
                .isInstanceOf(InspectionException.class).hasMessageContaining("CONFIGURATION")
                .hasMessageNotContaining(temp.toString()).hasNoCause();
    }

    @Test
    void closingOneInspectorIsIdempotentAndDoesNotAffectOthers() throws Exception {
        try (OnnxContentInspector other = inspector("binary")) {
            OnnxContentInspector first = inspector("binary");
            first.close();
            first.close();
            assertThat(first.inspect(request("hello")).failure()).isEqualTo(InspectionFailureCode.CONFIGURATION);
            assertThat(other.inspect(request("hello")).status()).isEqualTo(InspectionResult.Status.COMPLETED);
        }
    }

    @Test
    void configuredPaddingReachesTheActualOnnxInputs() throws Exception {
        Files.writeString(tokenizerConfig, """
                {"pad_token":{"content":"word7"},"padding_side":"left","pad_token_type_id":3}
                """);
        var profile = classification();
        var smallWindow = new OnnxClassificationConfig(tokenizer, tokenizerConfig, 8, 1, 2,
                SOFTMAX, profile.labels());
        try (OnnxContentInspector inspector = new OnnxContentInspector("padding",
                OnnxInspectionConfig.defaults(graph("padding")), smallWindow)) {
            assertThat(inspector.inspect(request("hello world")).findings()).singleElement()
                    .satisfies(finding -> assertThat(finding.score()).isCloseTo(1 / (1 + Math.exp(-7)), within(1e-12)));
        }
        Files.writeString(tokenizerConfig, "{\"pad_token\":\"word7\",\"padding_side\":\"right\",\"pad_token_type_id\":3}");
        try (OnnxContentInspector inspector = new OnnxContentInspector("padding",
                OnnxInspectionConfig.defaults(graph("padding")), smallWindow)) {
            assertThat(inspector.inspect(request("hello world")).findings()).isEmpty();
        }
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
                    return new double[] {0.01, 0.99};
                }));
                OnnxContentInspector inspector = inspector("binary")) {
            Future<InspectionResult> active = pool.submit(() -> inspector.inspect(request("attack")));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                InspectionResult queued = inspector.inspect(request(Duration.ofMillis(50), 10, "hello"));
                assertThat(queued.failure()).isEqualTo(InspectionFailureCode.TIMEOUT);
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
    @CsvSource({
            "false, , TIMEOUT",
            "true, , CANCELLED",
            "false, MODEL_ERROR, TIMEOUT",
            "true, MODEL_ERROR, CANCELLED",
            "false, INVALID_RESULT, INVALID_RESULT",
            "true, INVALID_RESULT, CANCELLED"
    })
    void activityIsCheckedAfterPredictionAndPreservesEarlierEvidence(
            boolean interrupt, InspectionFailureCode predictionFailure, InspectionFailureCode expected) throws Exception {
        AtomicReference<InspectionRequest> activeRequest = new AtomicReference<>();
        try (MockedConstruction<OnnxSequenceClassifier> ignored = mockConstruction(
                OnnxSequenceClassifier.class, (classifier, context) ->
                when(classifier.classify(any())).thenReturn(new double[] {0.01, 0.99}).thenAnswer(invocation -> {
                    if (interrupt) {
                        Thread.currentThread().interrupt();
                    } else {
                        TimeUnit.NANOSECONDS.sleep(activeRequest.get().remaining().toNanos());
                    }
                    if (predictionFailure == InspectionFailureCode.INVALID_RESULT) {
                        throw new IllegalStateException("Synthetic inference failure");
                    }
                    if (predictionFailure != null) {
                        throw new InspectionException(predictionFailure);
                    }
                    return new double[] {0.01, 0.99};
                }));
                OnnxContentInspector inspector = inspector("binary")) {
            InspectionRequest request = request(Duration.ofSeconds(1), 10, "attack", "attack");
            activeRequest.set(request);
            try {
                InspectionResult result = inspector.inspect(request);
                assertThat(result.failure()).isEqualTo(expected);
                assertThat(result.completedSegmentIds()).containsExactly("s0");
                assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("s0");
                assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupt);
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void concurrentRequestsKeepTheirCoverageAndScoresSeparate() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try (OnnxContentInspector inspector = inspector("binary")) {
            List<Future<InspectionResult>> pending = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                String text = i % 2 == 0 ? "hello" : "attack";
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

    @Test
    void interruptionBeforeTokenizationIsPreserved() throws Exception {
        try (OnnxContentInspector inspector = inspector("binary")) {
            try {
                Thread.currentThread().interrupt();
                assertThat(inspector.inspect(request("hello")).failure()).isEqualTo(InspectionFailureCode.CANCELLED);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void modelAndTokenizerPathsStayOutOfDiagnostics() {
        Path secret = temp.resolve("private-model.onnx");
        assertThat(OnnxInspectionConfig.defaults(secret).toString()).doesNotContain(secret.toString());
        assertThat(classification().toString()).doesNotContain(temp.toString());
        assertThatThrownBy(() -> new OnnxContentInspector("local", OnnxInspectionConfig.defaults(secret), classification()))
                .isInstanceOf(InspectionException.class).hasMessageContaining("CONFIGURATION")
                .hasMessageNotContaining("private-model").hasNoCause();
    }
}
