package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailure;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Native runtime and tokenizer integration tests using a tiny deterministic graph. */
class PromptGuardContentInspectorTest {

    @TempDir Path temp;

    private PromptGuardConfig config() throws Exception {
        byte[] encoded;
        try (var input = getClass().getResourceAsStream("/inspection-fixture.onnx.base64")) {
            encoded = Base64.getMimeDecoder().decode(input.readAllBytes());
        }
        Path model = Files.write(temp.resolve("fixture.onnx"), encoded);
        Path tokenizer;
        try (var input = getClass().getResourceAsStream("/inspection-fixture-tokenizer.json")) {
            tokenizer = Files.write(temp.resolve("tokenizer.json"), input.readAllBytes());
        }
        return PromptGuardConfig.defaults(model, tokenizer);
    }

    private PromptGuardContentInspector inspector() throws Exception {
        return new PromptGuardContentInspector(config());
    }

    @Test
    void tokenizerMatchesKnownFixtureTokenIds() throws Exception {
        var config = config();
        try (var tokenizer =
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

    private InspectionRequest request(String text, int chunks) {
        return new InspectionRequest(
                List.of(
                        new ContentSegment(
                                "s1",
                                ContentSegment.Source.UNKNOWN,
                                ContentSegment.Role.USER,
                                ContentSegment.Representation.RAW,
                                text)),
                new InspectionLimits(4, 131072, chunks, Duration.ofSeconds(10)));
    }

    @Test
    void realOnnxAndTokenizerClassifyBothFixtureLabels() throws Exception {
        try (var inspector = inspector()) {
            var benign = inspector.inspect(request("hello world", 16));
            assertThat(benign.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(benign.findings()).isEmpty();
            var malicious = inspector.inspect(request("hello attack", 16));
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
        try (var inspector = inspector()) {
            var result = inspector.inspect(request("hello ".repeat(1100) + "attack", 16));
            assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.inspectedSegmentIds()).containsExactly("s1");
            assertThat(result.findings()).isNotEmpty();
        }
    }

    @Test
    void exceedingWindowBudgetFailsWithoutClaimingCompleteCoverage() throws Exception {
        try (var inspector = inspector()) {
            var result = inspector.inspect(request("hello ".repeat(1100) + "attack", 1));
            assertThat(result.failure()).isEqualTo(InspectionFailure.LIMIT_EXCEEDED);
            assertThat(result.inspectedSegmentIds()).isEmpty();
        }
    }

    @Test
    void closeIsIdempotentAndPreventsFurtherRuns() throws Exception {
        var inspector = inspector();
        inspector.close();
        inspector.close();
        assertThat(inspector.inspect(request("hello", 16)).failure())
                .isEqualTo(InspectionFailure.CONFIGURATION);
    }

    @Test
    void interruptionIsPreserved() throws Exception {
        try (var inspector = inspector()) {
            try {
                Thread.currentThread().interrupt();
                assertThatThrownBy(() -> inspector.inspect(request("hello", 16)))
                        .hasMessageContaining("CANCELLED");
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void configurationRejectsMissingArtifactsWithoutExposingPaths() {
        assertThatThrownBy(
                        () ->
                                new PromptGuardContentInspector(
                                        PromptGuardConfig.defaults(
                                                temp.resolve("private-model-name.onnx"),
                                                temp.resolve("private-tokenizer.json"))))
                .hasMessageContaining("CONFIGURATION")
                .hasMessageNotContaining("private-");
    }
}
