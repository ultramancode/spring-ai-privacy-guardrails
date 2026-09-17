package io.github.ultramancode.springai.privacy.inspection.onnx;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionDecision;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailure;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Tokenization, inference and policy integration tests with a provisioned Prompt Guard ONNX export. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "INSPECTION_ONNX_MODEL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "INSPECTION_ONNX_TOKENIZER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "INSPECTION_ONNX_REFERENCE", matches = ".+")
class PromptGuardLiveTest {

    private static Path model;
    private static Path tokenizer;
    private static Properties reference;

    @BeforeAll
    static void verifyArtifacts() throws Exception {
        model = Path.of(System.getenv("INSPECTION_ONNX_MODEL"));
        tokenizer = Path.of(System.getenv("INSPECTION_ONNX_TOKENIZER"));
        reference = new Properties();
        try (InputStream input =
                Files.newInputStream(Path.of(System.getenv("INSPECTION_ONNX_REFERENCE")))) {
            reference.load(input);
        }
        assertThat(fileHash(model)).isEqualTo(reference.getProperty("model.sha256"));
        assertThat(fileHash(tokenizer)).isEqualTo(reference.getProperty("tokenizer.sha256"));
        assertThat(OrtEnvironment.getEnvironment().getVersion())
                .as("Regenerate the Python reference with the Java ONNX Runtime version")
                .isEqualTo(reference.getProperty("python.onnxruntime"));
        assertThat(Integer.parseInt(reference.getProperty("cases"))).isGreaterThanOrEqualTo(8);
    }

    @Test
    void realTokenizerWindowsAndInspectorScoresMatchPythonReference() throws Exception {
        // A diagnostic-only near-zero threshold exposes all scores through the existing public API.
        PromptGuardConfig config = new PromptGuardConfig(model, tokenizer, 1e-12, 64, 2);
        try (PromptGuardContentInspector inspector = new PromptGuardContentInspector(config);
                HuggingFaceTokenizer encoder =
                        HuggingFaceTokenizer.newInstance(
                                tokenizer,
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
            int count = Integer.parseInt(reference.getProperty("cases"));
            for (int i = 0; i < count; i++) {
                String prefix = "case." + i + ".";
                String name = reference.getProperty(prefix + "name");
                String text = text(prefix);
                int windows = Integer.parseInt(reference.getProperty(prefix + "windows"));
                Encoding first = encoder.encode(text);
                List<Encoding> encodings = new ArrayList<>();
                encodings.add(first);
                encodings.addAll(List.of(first.getOverflowing()));
                assertThat(encodings).as(name + " window count").hasSize(windows);
                for (int w = 0; w < windows; w++) {
                    String window = prefix + "window." + w + ".";
                    assertThat(tensorHash(encodings.get(w).getIds()))
                            .as(name + " token IDs " + w)
                            .isEqualTo(reference.getProperty(window + "input_ids.sha256"));
                    assertThat(tensorHash(encodings.get(w).getAttentionMask()))
                            .as(name + " mask " + w)
                            .isEqualTo(reference.getProperty(window + "attention_mask.sha256"));
                }
                InspectionResult result = inspector.inspect(request(text, 16));
                assertThat(result.status())
                        .as(name + " completion: " + result.failure())
                        .isEqualTo(InspectionResult.Status.COMPLETED);
                assertThat(result.inspectedSegmentIds()).containsExactly("synthetic");
                assertThat(result.findings()).as(name + " scored windows").hasSize(windows);
                for (int w = 0; w < windows; w++) {
                    double expected =
                            Double.parseDouble(
                                    reference.getProperty(prefix + "window." + w + ".score"));
                    double actual = result.findings().get(w).score();
                    assertThat(actual).as(name + " score " + w).isCloseTo(expected, within(1e-4));
                    System.out.printf(
                            Locale.ROOT,
                            "PROMPT_GUARD case=%s window=%d python=%.9f java=%.9f delta=%.9g%n",
                            name,
                            w,
                            expected,
                            actual,
                            Math.abs(expected - actual));
                }
            }
        }
    }

    @Test
    void defaultPolicyAllowsBenignAndBlocksAnEnglishInjection() {
        try (PromptGuardContentInspector inspector =
                new PromptGuardContentInspector(PromptGuardConfig.defaults(model, tokenizer))) {
            InspectionService service = new InspectionService(List.of(inspector));
            assertThat(service.inspect(request(text("case.0."), 16)).decision())
                    .isEqualTo(InspectionDecision.ALLOW);
            assertThat(service.inspect(request(text("case.1."), 16)).decision())
                    .isEqualTo(InspectionDecision.BLOCK);
        }
    }

    @Test
    void realModelChecksTheTailAndRejectsInsufficientWindowBudget() {
        assertThat(reference.getProperty("case.7.name")).isEqualTo("tail_attack");
        assertThat(Integer.parseInt(reference.getProperty("case.7.windows"))).isGreaterThan(1);
        try (PromptGuardContentInspector inspector =
                new PromptGuardContentInspector(PromptGuardConfig.defaults(model, tokenizer))) {
            InspectionResult complete = inspector.inspect(request(text("case.7."), 16));
            assertThat(complete.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(complete.findings()).isNotEmpty();
            InspectionResult limited = inspector.inspect(request(text("case.7."), 1));
            assertThat(limited.failure()).isEqualTo(InspectionFailure.LIMIT_EXCEEDED);
            assertThat(limited.inspectedSegmentIds()).isEmpty();
        }
    }

    private static String text(String prefix) {
        return new String(
                Base64.getDecoder().decode(reference.getProperty(prefix + "text")),
                StandardCharsets.UTF_8);
    }

    private static InspectionRequest request(String text, int chunks) {
        return new InspectionRequest(
                List.of(
                        new ContentSegment(
                                "synthetic",
                                ContentSegment.Source.USER,
                                ContentSegment.Role.USER,
                                ContentSegment.Representation.RAW,
                                text)),
                new InspectionLimits(4, 131072, chunks, Duration.ofSeconds(60)));
    }

    private static String fileHash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String tensorHash(long[] values) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Long.BYTES);
        for (long value : values) {
            buffer.putLong(value);
        }
        return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(buffer.array()));
    }
}
