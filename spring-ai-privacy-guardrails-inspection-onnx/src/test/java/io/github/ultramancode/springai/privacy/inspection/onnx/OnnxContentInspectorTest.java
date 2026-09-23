package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Native runtime and tokenizer integration tests using a tiny deterministic graph. */
class OnnxContentInspectorTest {

    @TempDir Path temp;

    private Artifacts config() throws Exception {
        byte[] encoded;
        try (InputStream input = getClass().getResourceAsStream("/inspection-fixture.onnx.base64")) {
            encoded = Base64.getMimeDecoder().decode(input.readAllBytes());
        }
        Path model = Files.write(temp.resolve("fixture.onnx"), encoded);
        Path tokenizer;
        try (InputStream input = getClass().getResourceAsStream("/inspection-fixture-tokenizer.json")) {
            tokenizer = Files.write(temp.resolve("tokenizer.json"), input.readAllBytes());
        }
        return new Artifacts(model, tokenizer);
    }

    private record Artifacts(Path model, Path tokenizer) { }

    private OnnxContentInspector inspector(Artifacts artifacts) {
        return new OnnxContentInspector(OnnxInspectionConfig.defaults(artifacts.model()),
                new PromptGuard2Model(artifacts.tokenizer()));
    }

    private OnnxContentInspector inspector() throws Exception {
        return inspector(config());
    }

    @Test
    void tokenizerMatchesKnownFixtureTokenIds() throws Exception {
        Artifacts config = config();
        try (HuggingFaceTokenizer tokenizer =
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
                                "64",
                                "withOverflowingTokens",
                                "true",
                                "addSpecialTokens",
                                "true"))) {
            assertThat(tokenizer.encode("hello attack").getIds()).startsWith(2, 4, 20, 3);
        }
    }

    private InspectionRequest request(String text) {
        return new InspectionRequest(
                List.of(
                        new ContentSegment(
                                "s1",
                                ContentSegment.Role.USER,
                                ContentSegment.PrivacyProcessingStatus.UNPROCESSED,
                                text)),
                new InspectionLimits(4, 131072, Duration.ofSeconds(10)));
    }

    @Test
    void realOnnxAndTokenizerClassifyBothFixtureLabels() throws Exception {
        try (OnnxContentInspector inspector = inspector()) {
            InspectionResult benign = inspector.inspect(request("hello world"));
            assertThat(benign.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(benign.findings()).isEmpty();
            InspectionResult malicious = inspector.inspect(request("hello attack"));
            assertThat(malicious.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(malicious.findings())
                    .singleElement()
                    .satisfies(
                            finding -> {
                                assertThat(finding.category())
                                        .isEqualTo(InspectionFinding.Category.PROMPT_ATTACK);
                                assertThat(finding.score()).isGreaterThan(0.99);
                            });
        }
    }

    @Test
    void tailBeyond512TokensIsNotSilentlyTruncated() throws Exception {
        try (OnnxContentInspector inspector = inspector()) {
            InspectionResult result = inspector.inspect(request("hello ".repeat(1100) + "attack"));
            assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.completedSegmentIds()).containsExactly("s1");
            assertThat(result.findings()).isNotEmpty();
        }
    }

    @Test
    void exceedingWindowBudgetFailsWithoutClaimingCompleteCoverage() throws Exception {
        Artifacts artifacts = config();
        try (OnnxContentInspector inspector = new OnnxContentInspector(
                new OnnxInspectionConfig(artifacts.model(), 2, 1), new PromptGuard2Model(artifacts.tokenizer()))) {
            InspectionResult result = inspector.inspect(request("hello ".repeat(1100) + "attack"));
            assertThat(result.failure()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
            assertThat(result.completedSegmentIds()).isEmpty();
        }
    }

    @Test
    void closeIsIdempotentAndPreventsFurtherRuns() throws Exception {
        OnnxContentInspector inspector = inspector();
        inspector.close();
        inspector.close();
        assertThat(inspector.inspect(request("hello")).failure())
                .isEqualTo(InspectionFailureCode.CONFIGURATION);
    }

    @Test
    void interruptionIsPreserved() throws Exception {
        try (OnnxContentInspector inspector = inspector()) {
            try {
                Thread.currentThread().interrupt();
                assertThat(inspector.inspect(request("hello")).failure()).isEqualTo(InspectionFailureCode.CANCELLED);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    private Path fixture(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/" + name + ".onnx.base64")) {
            return Files.write(temp.resolve(name + ".onnx"), Base64.getMimeDecoder().decode(input.readAllBytes()));
        }
    }

    @Test
    void int32InputsWithTokenTypesAreCreatedFromTheGraphsMetadata() throws Exception {
        Artifacts artifacts = config();
        try (OnnxContentInspector inspector = new OnnxContentInspector(
                OnnxInspectionConfig.defaults(fixture("inspection-int32")),
                new ProtectAiDebertaV2Model(artifacts.tokenizer()))) {
            InspectionResult result = inspector.inspect(request("hello attack"));
            assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.findings()).singleElement().satisfies(finding ->
                    assertThat(finding.category()).isEqualTo(InspectionFinding.Category.PROMPT_INJECTION));
        }
    }

    @Test
    void runtimeDoesNotAssume512TokenWindows() throws Exception {
        Artifacts artifacts = config();
        // This tokenizer uses a nonzero pad ID, as the ModernBERT exports do.
        String tokenizerJson = Files.readString(artifacts.tokenizer())
                .replace("\"id\": 0", "\"id\": 7")
                .replace("\"[PAD]\": 0", "\"[PAD]\": 7")
                .replace("\"word7\": 7", "\"word7\": 0");
        Files.writeString(artifacts.tokenizer(), tokenizerJson);
        Path tokenizerConfig = Files.writeString(temp.resolve("tokenizer_config.json"),
                "{\"pad_token\":\"[PAD]\",\"model_max_length\":8192}");
        OnnxInspectionModel model = new HuggingFaceSequenceClassifier("long-fixture", artifacts.tokenizer(), tokenizerConfig,
                8192, 64, 2, HuggingFaceSequenceClassifier.Activation.SOFTMAX,
                List.of(new HuggingFaceSequenceClassifier.Label(1,
                        InspectionFinding.Category.PROMPT_INJECTION, "INJECTION")), 0.5);
        try (OnnxContentInspector inspector = new OnnxContentInspector(
                OnnxInspectionConfig.defaults(artifacts.model()), model)) {
            assertThat(model.encode("hello", 1).get(0).get("input_ids")).hasSize(8192).endsWith(7, 7);
            InspectionResult result = inspector.inspect(request("hello ".repeat(6000) + "attack"));
            assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.findings()).hasSize(1);
            assertThat(result.completedSegmentIds()).containsExactly("s1");
        }
    }

    @Test
    void independentSigmoidLabelsAreNotReducedToBinarySoftmax() throws Exception {
        Artifacts artifacts = config();
        OnnxInspectionModel model = new HuggingFaceSequenceClassifier("multilabel-fixture", artifacts.tokenizer(),
                1024, 64, 3, HuggingFaceSequenceClassifier.Activation.SIGMOID,
                List.of(new HuggingFaceSequenceClassifier.Label(0, InspectionFinding.Category.PROMPT_ATTACK, "A"),
                        new HuggingFaceSequenceClassifier.Label(1, InspectionFinding.Category.PROMPT_INJECTION, "B"),
                        new HuggingFaceSequenceClassifier.Label(2, InspectionFinding.Category.PROMPT_LEAKING, "C")), 0.5);
        try (OnnxContentInspector inspector = new OnnxContentInspector(
                OnnxInspectionConfig.defaults(fixture("inspection-multilabel")), model)) {
            InspectionResult result = inspector.inspect(request("hello attack"));
            assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.findings()).extracting(InspectionFinding::code).containsExactly("A", "B");
        }
    }

    @Test
    void mismatchedOutputShapeIsRejectedBeforeInspection() throws Exception {
        Artifacts artifacts = config();
        Path model = fixture("inspection-multilabel");
        assertThatThrownBy(() -> new OnnxContentInspector(OnnxInspectionConfig.defaults(model),
                new PromptGuard2Model(artifacts.tokenizer())))
                .isInstanceOf(InspectionException.class).hasMessageContaining("CONFIGURATION");
    }

    @ParameterizedTest
    @EnumSource(value = InspectionFailureCode.class, names = {"MODEL_ERROR", "CANCELLED"})
    void laterWindowFailureKeepsEarlierEvidenceAndOnlyFullyCoveredSegments(InspectionFailureCode failure) throws Exception {
        Artifacts artifacts = config();
        OnnxInspectionModel model = new HuggingFaceSequenceClassifier("failure-fixture", artifacts.tokenizer(),
                512, 64, 2, HuggingFaceSequenceClassifier.Activation.SOFTMAX,
                List.of(new HuggingFaceSequenceClassifier.Label(1, InspectionFinding.Category.PROMPT_ATTACK, "ATTACK")), 0.5) {
            private int runs;

            @Override
            public List<InspectionFinding> decode(String segmentId, OrtSession.Result outputs) throws OrtException {
                if (++runs == 3) {
                    if (failure == InspectionFailureCode.CANCELLED) {
                        Thread.currentThread().interrupt();
                    }
                    throw new InspectionException(failure);
                }
                return super.decode(segmentId, outputs);
            }
        };
        InspectionRequest request = new InspectionRequest(List.of(
                new ContentSegment("complete", ContentSegment.Role.USER,
                        ContentSegment.PrivacyProcessingStatus.UNPROCESSED, "hello"),
                new ContentSegment("partial", ContentSegment.Role.USER,
                        ContentSegment.PrivacyProcessingStatus.UNPROCESSED, "attack ".repeat(700))),
                new InspectionLimits(4, 131072, Duration.ofSeconds(10)));
        try (OnnxContentInspector inspector = new OnnxContentInspector(
                "local-guard", OnnxInspectionConfig.defaults(artifacts.model()), model)) {
            assertThat(inspector.inspectorId()).isEqualTo("local-guard");
            InspectionResult result = inspector.inspect(request);
            assertThat(result.failure()).isEqualTo(failure);
            assertThat(result.completedSegmentIds()).containsExactly("complete");
            assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("partial");
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(failure == InspectionFailureCode.CANCELLED);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void configurationRejectsMissingArtifactsWithoutExposingPaths() {
        assertThatThrownBy(
                        () ->
                                new OnnxContentInspector(
                                        OnnxInspectionConfig.defaults(temp.resolve("private-model-name.onnx")),
                                        new PromptGuard2Model(temp.resolve("private-tokenizer.json"))))
                .hasMessageContaining("CONFIGURATION")
                .hasMessageNotContaining("private-");
    }
}
