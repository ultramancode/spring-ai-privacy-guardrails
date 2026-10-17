package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.sun.net.httpserver.HttpServer;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol.GuardModelProtocol;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol.KananaSafeguardPromptProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

import static io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding.Category.PROMPT_INJECTION;
import static io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding.Category.PROMPT_LEAKING;
import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleWindowInspectionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final List<String> receivedWindows = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private URI baseUrl;

    @AfterEach
    void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void startServer(Function<String, String> verdict) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                JsonNode messages = JSON.readTree(exchange.getRequestBody()).path("messages");
                String text = messages.get(messages.size() - 1).path("content").asText();
                receivedWindows.add(text);
                String output = verdict.apply(text);
                Map<String, String> message = Map.of("role", "assistant", "content", output);
                Map<String, Object> choice = Map.of("finish_reason", "stop", "message", message);
                Map<String, Object> responseBody = Map.of("choices", List.of(choice));
                byte[] response = JSON.writeValueAsString(responseBody)
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        baseUrl = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    private OpenAiCompatibleInspectionConfig config() {
        return new OpenAiCompatibleInspectionConfig(baseUrl, "fixture", null, Duration.ofSeconds(2), 4096, true);
    }

    private OpenAiCompatibleContentInspector inspector(TextWindowConfig windowConfig) {
        return new OpenAiCompatibleContentInspector("guard", config(), new KananaSafeguardPromptProtocol(), windowConfig);
    }

    private InspectionRequest request(String... texts) {
        List<ContentSegment> segments = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            segments.add(new ContentSegment("s" + i, ContentSegment.Role.USER,
                    ContentSegment.PrivacyProcessingStatus.PROCESSED, texts[i]));
        }
        return new InspectionRequest(segments, InspectionLimits.defaults());
    }

    @Test
    void sendsTheCompleteTextWhenWindowingIsNotConfigured() throws Exception {
        startServer(text -> "<SAFE>");
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config(), new KananaSafeguardPromptProtocol());
        String text = "한글 😀 with whitespace\n".repeat(300);

        InspectionResult result = inspector.inspect(request(text));

        assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(result.completedSegmentIds()).containsExactly("s0");
        assertThat(receivedWindows).containsExactly(text);
    }

    @ParameterizedTest
    @CsvSource({"0,abcd|efgh|i", "1,abcd|defg|ghi", "3,abcd|bcde|cdef|defg|efgh|fghi"})
    void windowsCoverTheTailAndKeepTheOriginalSegmentId(int overlapSize, String expected) throws Exception {
        startServer(text -> text.endsWith("i") ? "<UNSAFE-A1>" : "<SAFE>");
        String[] expectedWindows = expected.split("\\|");
        OpenAiCompatibleContentInspector inspector = inspector(new TextWindowConfig(4, overlapSize, expectedWindows.length));

        InspectionResult result = inspector.inspect(request("abcdefghi"));

        assertThat(receivedWindows).containsExactly(expectedWindows);
        assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(result.completedSegmentIds()).containsExactly("s0");
        assertThat(result.findings()).containsExactly(new InspectionFinding("s0", PROMPT_INJECTION, "UNSAFE-A1", null));
    }

    @Test
    void keepsEmojiIntactAcrossOverlappingWindows() throws Exception {
        startServer(text -> "<SAFE>");

        InspectionResult result = inspector(new TextWindowConfig(3, 1, 3)).inspect(request("A😀한B🚀글C"));

        assertThat(receivedWindows).containsExactly("A😀한", "한B🚀", "🚀글C");
        assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void inspectsEmptyAndBlankSegments(String text) throws Exception {
        startServer(window -> "<SAFE>");

        InspectionResult result = inspector(new TextWindowConfig(4, 1, 1)).inspect(request(text));

        assertThat(receivedWindows).containsExactly(text);
        assertThat(result.completedSegmentIds()).containsExactly("s0");
        assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
    }

    @Test
    void repeatedVerdictsAreCollectedOncePerSegmentAndCode() throws Exception {
        startServer(text -> switch (text) {
            case "ab", "ef" -> "<UNSAFE-A1>";
            default -> "<UNSAFE-A2>";
        });

        InspectionResult result = inspector(new TextWindowConfig(2, 0, 8))
                .inspect(request("abcdefgh", "abcdefgh"));

        assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("s0", "s0", "s1", "s1");
        assertThat(result.findings()).extracting(InspectionFinding::code)
                .containsExactly("UNSAFE-A1", "UNSAFE-A2", "UNSAFE-A1", "UNSAFE-A2");
    }

    @Test
    void highestAvailableScoresAreKeptWithoutMergingDifferentCategories() throws Exception {
        startServer(text -> text);
        GuardModelProtocol protocol = new GuardModelProtocol() {
            @Override
            public ChatCompletionCreateParams request(String model, String text) {
                return ChatCompletionCreateParams.builder().model(model).addUserMessage(text).build();
            }

            @Override
            public Optional<InspectionFinding> parse(String segmentId, String output) {
                if (output.equals("f")) {
                    return Optional.of(new InspectionFinding(segmentId, PROMPT_LEAKING, "RISK", 0.4));
                }
                Double score = switch (output) {
                    case "b" -> 0.6;
                    case "c" -> 0.8;
                    case "d" -> 0.7;
                    default -> null;
                };
                return Optional.of(new InspectionFinding(segmentId, PROMPT_INJECTION, "RISK", score));
            }
        };
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config(), protocol, new TextWindowConfig(1, 0, 6));

        InspectionResult result = inspector.inspect(request("abcdef"));

        assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(result.findings()).containsExactly(
                new InspectionFinding("s0", PROMPT_INJECTION, "RISK", 0.8),
                new InspectionFinding("s0", PROMPT_LEAKING, "RISK", 0.4));
    }

    @Test
    void laterWindowFailurePreservesPartialFindings() throws Exception {
        startServer(text -> text.equals("cc") ? "invalid verdict" : "<UNSAFE-A1>");

        InspectionResult result = inspector(new TextWindowConfig(2, 0, 3)).inspect(request("aa", "bbcc"));

        assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESPONSE);
        assertThat(result.completedSegmentIds()).containsExactly("s0");
        assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("s0", "s1");
        assertThat(receivedWindows).containsExactly("aa", "bb", "cc");
    }

    @Test
    void windowLimitAppliesAcrossSegmentsAndPreservesPartialEvidence() throws Exception {
        startServer(text -> "<UNSAFE-A1>");

        InspectionResult result = inspector(new TextWindowConfig(2, 1, 3)).inspect(request("ab", "cdef"));

        assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
        assertThat(result.completedSegmentIds()).containsExactly("s0");
        assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("s0", "s1");
        assertThat(receivedWindows).containsExactly("ab", "cd", "de");
    }

    @Test
    void elapsedDeadlineKeepsTheLastVerdictAndPreventsFurtherWindows() throws Exception {
        startServer(text -> "<UNSAFE-A1>");
        AtomicReference<InspectionRequest> activeRequest = new AtomicReference<>();
        GuardModelProtocol delegate = new KananaSafeguardPromptProtocol();
        GuardModelProtocol protocol = new GuardModelProtocol() {
            @Override
            public ChatCompletionCreateParams request(String model, String text) {
                return delegate.request(model, text);
            }

            @Override
            public Optional<InspectionFinding> parse(String segmentId, String output) {
                while (true) {
                    try {
                        LockSupport.parkNanos(activeRequest.get().remaining().toNanos());
                    } catch (InspectionException expired) {
                        assertThat(expired.failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
                        return delegate.parse(segmentId, output);
                    }
                }
            }
        };
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config(), protocol, new TextWindowConfig(2, 0, 2));
        InspectionRequest request = new InspectionRequest(request("aabb").segments(),
                new InspectionLimits(1, 100, Duration.ofSeconds(2)));
        activeRequest.set(request);

        InspectionResult result = inspector.inspect(request);

        assertThat(receivedWindows).containsExactly("aa");
        assertThat(result.findings()).hasSize(1);
        assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
        assertThat(result.completedSegmentIds()).isEmpty();
    }

    @Test
    void interruptionDuringParsingKeepsTheVerdictAndCancelsInspection() throws Exception {
        startServer(text -> "<UNSAFE-A1>");
        GuardModelProtocol delegate = new KananaSafeguardPromptProtocol();
        GuardModelProtocol protocol = new GuardModelProtocol() {
            @Override
            public ChatCompletionCreateParams request(String model, String text) {
                return delegate.request(model, text);
            }

            @Override
            public Optional<InspectionFinding> parse(String segmentId, String output) {
                Thread.currentThread().interrupt();
                return delegate.parse(segmentId, output);
            }
        };
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config(), protocol, new TextWindowConfig(2, 0, 1));

        try {
            InspectionResult result = inspector.inspect(request("aa"));

            assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);
            assertThat(result.completedSegmentIds()).isEmpty();
            assertThat(result.findings()).hasSize(1);
            assertThat(receivedWindows).containsExactly("aa");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
