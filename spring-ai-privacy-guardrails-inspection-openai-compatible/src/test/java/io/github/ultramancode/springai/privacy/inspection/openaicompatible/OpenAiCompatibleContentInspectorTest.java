package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionDecision;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailure;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleContentInspectorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> response = new AtomicReference<>();
    private int status = 200;
    private long delayMillis;
    private URI endpoint;

    private OpenAiCompatibleContentInspector start(String output) throws Exception {
        response.set(envelope(output));
        startServer(exchange -> {
            try {
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        return OpenAiCompatibleContentInspector.kanana(config(false, Duration.ofSeconds(2), 4096));
    }

    private void startServer(HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            handler.handle(exchange);
        });
        server.setExecutor(executor);
        server.start();
        endpoint =
                URI.create(
                        "http://127.0.0.1:"
                                + server.getAddress().getPort()
                                + "/v1/chat/completions");
    }

    private OpenAiCompatibleInspectionConfig config(boolean raw, Duration timeout, int bytes) {
        return new OpenAiCompatibleInspectionConfig(
                endpoint, "test-model", "test-key", timeout, bytes, raw);
    }

    private InspectionRequest request(ContentSegment.Representation form, String text) {
        return new InspectionRequest(
                List.of(
                        new ContentSegment(
                                "s1",
                                ContentSegment.Source.USER,
                                ContentSegment.Role.USER,
                                form,
                                text)),
                InspectionLimits.defaults());
    }

    private InspectionRequest protectedRequest() {
        return request(ContentSegment.Representation.PRIVACY_PROTECTED, "customer [PII:1]");
    }

    private static String envelope(String content) {
        return JSON.writeValueAsString(
                Map.of(
                        "choices",
                        List.of(
                                Map.of(
                                        "finish_reason",
                                        "stop",
                                        "message",
                                        Map.of("role", "assistant", "content", content)))));
    }

    @AfterEach
    void close() {
        if (server != null) {
            server.stop(0);
        }
        executor.shutdownNow();
    }

    @Test
    void strictKananaMappingAndDedicatedHttpEnvelope() throws Exception {
        OpenAiCompatibleContentInspector inspector = start("<SAFE>");
        assertThat(inspector.inspect(protectedRequest()).findings()).isEmpty();
        JsonNode sent = JSON.readTree(received.get());
        assertThat(sent.path("messages").size()).isEqualTo(1);
        assertThat(sent.path("messages").get(0).path("role").asString()).isEqualTo("user");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(1);
        assertThat(sent.path("stream").asBoolean()).isFalse();
        assertThat(sent.path("add_generation_prompt").asBoolean()).isFalse();
        assertThat(sent.path("skip_special_tokens").asBoolean()).isFalse();
        response.set(envelope("<UNSAFE-A1>"));
        assertThat(inspector.inspect(protectedRequest()).findings())
                .singleElement()
                .extracting(InspectionFinding::category)
                .isEqualTo(InspectionFinding.Category.PROMPT_INJECTION);
        response.set(envelope("<UNSAFE-A2>"));
        assertThat(inspector.inspect(protectedRequest()).findings())
                .singleElement()
                .extracting(InspectionFinding::category)
                .isEqualTo(InspectionFinding.Category.PROMPT_LEAKING);
    }

    @Test
    void unprotectedContentNeverLeavesByDefault() throws Exception {
        OpenAiCompatibleContentInspector inspector = start("<SAFE>");
        assertThatThrownBy(
                        () ->
                                inspector.inspect(
                                        request(ContentSegment.Representation.RAW, "raw secret")))
                .hasMessageContaining("DISCLOSURE_DENIED");
        assertThatThrownBy(
                        () ->
                                inspector.inspect(
                                        request(
                                                ContentSegment.Representation.AS_RECEIVED,
                                                "raw secret")))
                .hasMessageContaining("DISCLOSURE_DENIED");
        assertThat(calls).hasValue(0);
    }

    @Test
    void explicitlyAuthorizedRawContentCanBeSent() throws Exception {
        start("<SAFE>");
        OpenAiCompatibleContentInspector inspector =
                OpenAiCompatibleContentInspector.kanana(config(true, Duration.ofSeconds(2), 4096));
        assertThat(
                        inspector
                                .inspect(
                                        request(ContentSegment.Representation.RAW, "synthetic raw"))
                                .status())
                .isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(received.get()).contains("synthetic raw");
    }

    @Test
    void unknownLabelsAndMalformedResponsesNeverBecomeSafe() throws Exception {
        OpenAiCompatibleContentInspector inspector = start("I think this is <SAFE>");
        for (String body :
                List.of(
                        envelope("I think this is <SAFE>"),
                        envelope(""),
                        envelope("<SAFE><UNSAFE-A1>"),
                        "{bad json",
                        "{}",
                        "{\"choices\":[]}",
                        envelope("<SAFE>") + "{}",
                        "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"<SAFE>\",\"tool_calls\":[{}]}}]}")) {
            response.set(body);
            InspectionReport report = new InspectionService(List.of(inspector)).inspect(protectedRequest());
            assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
            assertThat(report.outcomes().get(0).result().status())
                    .isEqualTo(InspectionResult.Status.FAILED);
        }
    }

    @Test
    void boundedResponsesAndHttpFailuresAreSanitized() throws Exception {
        start("<SAFE>");
        OpenAiCompatibleContentInspector inspector =
                OpenAiCompatibleContentInspector.kanana(config(false, Duration.ofSeconds(2), 128));
        response.set("secret".repeat(300));
        InspectionResult result = inspector.inspect(protectedRequest());
        assertThat(result.failure()).isEqualTo(InspectionFailure.LIMIT_EXCEEDED);
        assertThat(result.toString()).doesNotContain("secret");
        status = 503;
        response.set("raw failure details");
        assertThat(inspector.inspect(protectedRequest()).failure())
                .isEqualTo(InspectionFailure.HTTP_ERROR);
    }

    @Test
    void rejectsMalformedToolCallsEvenWhenTheNodeIsEmpty() throws Exception {
        OpenAiCompatibleContentInspector inspector = start("<SAFE>");
        for (Object malformedToolCalls : List.of("", "unexpected", Map.of(), 0, false)) {
            Map<String, Object> message =
                    Map.of("role", "assistant", "content", "<SAFE>", "tool_calls", malformedToolCalls);
            Map<String, Object> choice = Map.of("finish_reason", "stop", "message", message);
            response.set(JSON.writeValueAsString(Map.of("choices", List.of(choice))));
            assertThat(inspector.inspect(protectedRequest()).failure())
                    .isEqualTo(InspectionFailure.INVALID_RESPONSE);
        }
    }

    @Test
    void timeoutIsAnOperationalFailure() throws Exception {
        start("<SAFE>");
        delayMillis = 500;
        OpenAiCompatibleContentInspector inspector =
                OpenAiCompatibleContentInspector.kanana(config(false, Duration.ofMillis(50), 4096));
        assertThat(inspector.inspect(protectedRequest()).failure())
                .isEqualTo(InspectionFailure.TIMEOUT);
    }

    @Test
    void interruptionDuringResponseBodyStopsInspectionAndPreservesInterrupt() throws Exception {
        CountDownLatch partialBodyFlushed = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        startServer(exchange -> {
            try {
                byte[] bytes = envelope("<SAFE>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes, 0, 1);
                exchange.getResponseBody().flush();
                partialBodyFlushed.countDown();
                releaseBody.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        OpenAiCompatibleContentInspector inspector =
                OpenAiCompatibleContentInspector.kanana(config(false, Duration.ofSeconds(10), 4096));
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<InspectionFailure> failure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                inspector.inspect(protectedRequest());
                            } catch (InspectionException ex) {
                                failure.set(ex.failure());
                                interruptRestored.set(Thread.currentThread().isInterrupted());
                            } finally {
                                done.countDown();
                            }
                        });
        try {
            worker.start();
            assertThat(partialBodyFlushed.await(5, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(failure.get()).isEqualTo(InspectionFailure.CANCELLED);
            assertThat(interruptRestored).isTrue();
            assertThat(calls).hasValue(1);
        } finally {
            releaseBody.countDown();
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(2_000);
            }
        }
    }

    @Test
    void interruptedRequestNeverStartsHttp() throws Exception {
        OpenAiCompatibleContentInspector inspector = start("<SAFE>");
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> inspector.inspect(protectedRequest()))
                    .hasMessageContaining("CANCELLED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(calls).hasValue(0);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void strictJsonProtocolRejectsExtraFieldsAndTruncation() throws Exception {
        start("{\"verdict\":\"SAFE\"}");
        OpenAiCompatibleContentInspector inspector =
                OpenAiCompatibleContentInspector.jsonGuard(
                        config(false, Duration.ofSeconds(2), 4096));
        assertThat(inspector.inspect(protectedRequest()).status())
                .isEqualTo(InspectionResult.Status.COMPLETED);
        response.set(envelope("{\"verdict\":\"UNSAFE\"}"));
        assertThat(inspector.inspect(protectedRequest()).findings()).hasSize(1);
        for (String content :
                List.of(
                        "{\"verdict\":\"SAFE\",\"reason\":\"extra\"}",
                        "SAFE",
                        "{\"verdict\":\"MAYBE\"}",
                        "```json\n{\"verdict\":\"SAFE\"}\n```",
                        "{\"verdict\":\"SAFE\"} {}",
                        "{\"verdict\":\"UNSAFE\",\"verdict\":\"SAFE\"}")) {
            response.set(envelope(content));
            assertThat(inspector.inspect(protectedRequest()).status())
                    .isEqualTo(InspectionResult.Status.FAILED);
        }
        response.set(envelope("{\"verdict\":\"SAFE\"}").replace("\"stop\"", "\"length\""));
        assertThat(inspector.inspect(protectedRequest()).status())
                .isEqualTo(InspectionResult.Status.FAILED);
    }

    @Test
    void configurationDoesNotExposeCredentials() throws Exception {
        start("<SAFE>");
        assertThat(config(false, Duration.ofSeconds(2), 4096).toString())
                .doesNotContain("test-key", endpoint.toString());
    }
}
