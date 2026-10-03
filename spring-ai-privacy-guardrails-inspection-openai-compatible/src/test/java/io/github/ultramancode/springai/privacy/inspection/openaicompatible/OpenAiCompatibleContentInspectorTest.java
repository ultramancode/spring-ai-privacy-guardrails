package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionDecision;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionException;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailureCode;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFailurePolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionPolicy;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionReport;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
        return new OpenAiCompatibleContentInspector("guard", config(true, Duration.ofSeconds(2), 4096), new KananaPromptProtocol());
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

    private OpenAiCompatibleInspectionConfig config(boolean requirePrivacyProcessedContent, Duration timeout, int bytes) {
        return new OpenAiCompatibleInspectionConfig(
                endpoint, "test-model", "test-key", timeout, bytes, requirePrivacyProcessedContent);
    }

    private InspectionRequest request(ContentSegment.PrivacyProcessingStatus status, String text) {
        return new InspectionRequest(
                List.of(
                        new ContentSegment(
                                "s1",
                                ContentSegment.Role.USER,
                                status,
                                text)),
                InspectionLimits.defaults());
    }

    private InspectionRequest privacyProcessedRequest() {
        return request(ContentSegment.PrivacyProcessingStatus.PROCESSED, "customer [PII:1]");
    }

    @Test
    void endpointQueryIsPreservedInTheHttpRequest() throws Exception {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        startServer(exchange -> {
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = envelope("<SAFE>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        String query = "api-version=2026-01-01&route=guard%2Fprimary";
        endpoint = URI.create(endpoint + "?" + query);
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config(true, Duration.ofSeconds(2), 4096), new KananaPromptProtocol());

        InspectionResult result = inspector.inspect(privacyProcessedRequest());

        assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(receivedQuery).hasValue(query);
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
        assertThat(inspector.inspect(privacyProcessedRequest()).findings()).isEmpty();
        JsonNode sent = JSON.readTree(received.get());
        assertThat(sent.path("messages").size()).isEqualTo(1);
        assertThat(sent.path("messages").get(0).path("role").asString()).isEqualTo("user");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(1);
        assertThat(sent.path("stream").asBoolean()).isFalse();
        assertThat(sent.path("add_generation_prompt").asBoolean()).isFalse();
        assertThat(sent.path("skip_special_tokens").asBoolean()).isFalse();
        response.set(envelope("<UNSAFE-A1>"));
        assertThat(inspector.inspect(privacyProcessedRequest()).findings())
                .singleElement()
                .extracting(InspectionFinding::category)
                .isEqualTo(InspectionFinding.Category.PROMPT_INJECTION);
        response.set(envelope("<UNSAFE-A2>"));
        assertThat(inspector.inspect(privacyProcessedRequest()).findings())
                .singleElement()
                .extracting(InspectionFinding::category)
                .isEqualTo(InspectionFinding.Category.PROMPT_LEAKING);
    }

    @Test
    void requiredPrivacyProcessingRejectsUnknownAndUnprocessedContentBeforeHttp() throws Exception {
        OpenAiCompatibleContentInspector inspector = start("<SAFE>");
        assertThatThrownBy(
                        () ->
                                inspector.inspect(
                                        request(ContentSegment.PrivacyProcessingStatus.UNPROCESSED, "raw secret")))
                .hasMessageContaining("DISCLOSURE_DENIED");
        assertThatThrownBy(
                        () ->
                                inspector.inspect(
                                        request(
                                                ContentSegment.PrivacyProcessingStatus.UNKNOWN,
                                                "raw secret")))
                .hasMessageContaining("DISCLOSURE_DENIED");
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = ContentSegment.PrivacyProcessingStatus.class, names = {"UNKNOWN", "UNPROCESSED"})
    void disabledPrivacyRequirementAllowsStandaloneHttpInspection(ContentSegment.PrivacyProcessingStatus status) throws Exception {
        start("<SAFE>");
        OpenAiCompatibleContentInspector inspector =
                new OpenAiCompatibleContentInspector("guard", config(false, Duration.ofSeconds(2), 4096), new KananaPromptProtocol());
        InspectionService service = new InspectionService(List.of(inspector));

        InspectionReport report = service.inspect(request(status, "synthetic raw"));

        assertThat(report.decision()).isEqualTo(InspectionDecision.ALLOW);
        assertThat(report.outcomes().get(0).result().status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(received.get()).contains("synthetic raw");
    }

    @Test
    void configuredHttpBudgetsAcceptLargeResponsesAndStillEnforceTheirLimit() throws Exception {
        start("<SAFE>");
        response.set(envelope("<SAFE>") + " ".repeat(1_048_576));
        OpenAiCompatibleInspectionConfig config = config(true, Duration.ofMinutes(11), 2_097_152);
        OpenAiCompatibleContentInspector inspector =
                new OpenAiCompatibleContentInspector("guard", config, new KananaPromptProtocol());

        InspectionResult accepted = inspector.inspect(privacyProcessedRequest());

        assertThat(accepted.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        response.set(envelope("<SAFE>") + " ".repeat(config.maxResponseBytes()));

        InspectionResult rejected = inspector.inspect(privacyProcessedRequest());

        assertThat(rejected.failure()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
        assertThat(rejected.completedSegmentIds()).isEmpty();
    }

    @Test
    void longModelNamesAndCredentialsReachTheEndpointUnchanged() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = envelope("<SAFE>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        String model = "deployment-".repeat(30);
        String key = "k".repeat(4097);
        OpenAiCompatibleInspectionConfig configuration = new OpenAiCompatibleInspectionConfig(
                endpoint, model, key, Duration.ofSeconds(5), 4096, true);
        OpenAiCompatibleContentInspector inspector =
                new OpenAiCompatibleContentInspector("guard", configuration, new KananaPromptProtocol());
        assertThat(inspector.inspect(privacyProcessedRequest()).status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(JSON.readTree(received.get()).get("model").asString()).isEqualTo(model);
        assertThat(authorization.get()).isEqualTo("Bearer " + key);
        assertThatThrownBy(() -> new OpenAiCompatibleInspectionConfig(endpoint, "model", "key\r\ninjected",
                Duration.ofSeconds(5), 4096, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void httpBudgetsMustBePositiveAndTimeoutMustFitInNanoseconds() {
        endpoint = URI.create("http://localhost/v1/chat/completions");
        for (Duration timeout : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertThatThrownBy(() -> config(true, timeout, 4096)).isInstanceOf(IllegalArgumentException.class);
        }
        for (int bytes : new int[] {0, -1}) {
            assertThatThrownBy(() -> config(true, Duration.ofSeconds(10), bytes))
                    .isInstanceOf(IllegalArgumentException.class);
        }
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
            InspectionReport report = new InspectionService(List.of(inspector)).inspect(privacyProcessedRequest());
            assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
            assertThat(report.outcomes().get(0).result().status())
                    .isEqualTo(InspectionResult.Status.FAILED);
        }
    }

    @Test
    void boundedResponsesAndHttpFailuresAreSanitized() throws Exception {
        start("<SAFE>");
        OpenAiCompatibleContentInspector inspector =
                new OpenAiCompatibleContentInspector("guard", config(true, Duration.ofSeconds(2), 128), new KananaPromptProtocol());
        response.set("secret".repeat(300));
        InspectionResult result = inspector.inspect(privacyProcessedRequest());
        assertThat(result.failure()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
        assertThat(result.toString()).doesNotContain("secret");
        status = 503;
        response.set("raw failure details");
        assertThat(inspector.inspect(privacyProcessedRequest()).failure())
                .isEqualTo(InspectionFailureCode.HTTP_ERROR);
    }

    @Test
    void rejectsMalformedToolCallsEvenWhenTheNodeIsEmpty() throws Exception {
        OpenAiCompatibleContentInspector inspector = start("<SAFE>");
        for (Object malformedToolCalls : List.of("", "unexpected", Map.of(), 0, false)) {
            Map<String, Object> message =
                    Map.of("role", "assistant", "content", "<SAFE>", "tool_calls", malformedToolCalls);
            Map<String, Object> choice = Map.of("finish_reason", "stop", "message", message);
            response.set(JSON.writeValueAsString(Map.of("choices", List.of(choice))));
            assertThat(inspector.inspect(privacyProcessedRequest()).failure())
                    .isEqualTo(InspectionFailureCode.INVALID_RESPONSE);
        }
    }

    @Test
    void timeoutIsAnOperationalFailure() throws Exception {
        start("<SAFE>");
        delayMillis = 500;
        OpenAiCompatibleContentInspector inspector =
                new OpenAiCompatibleContentInspector("guard", config(true, Duration.ofMillis(50), 4096), new KananaPromptProtocol());
        assertThat(inspector.inspect(privacyProcessedRequest()).failure())
                .isEqualTo(InspectionFailureCode.TIMEOUT);
    }

    @Test
    void interruptionDuringResponseBodyStopsInspectionAndPreservesInterrupt() throws Exception {
        CountDownLatch partialBodyFlushed = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        startServer(exchange -> {
            try {
                if (calls.get() == 1) {
                    byte[] bytes = envelope("<UNSAFE-A1>").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    return;
                }
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
                new OpenAiCompatibleContentInspector("guard", config(true, Duration.ofSeconds(10), 4096), new KananaPromptProtocol());
        InspectionService service = new InspectionService(List.of(inspector),
                (id, findings) -> InspectionDecision.ALLOW, InspectionFailurePolicy.FAIL_OPEN);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<InspectionException> failure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread worker =
                new Thread(
                        () -> {
                            try {
                                service.inspect(twoSegments());
                            } catch (InspectionException ex) {
                                failure.set(ex);
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
            assertThat(failure.get()).isNotNull();
            assertThat(failure.get().failure()).isEqualTo(InspectionFailureCode.CANCELLED);
            InspectionResult partial = failure.get().report().orElseThrow().outcomes().get(0).result();
            assertThat(partial.completedSegmentIds()).containsExactly("first");
            assertThat(partial.findings()).extracting(InspectionFinding::segmentId).containsExactly("first");
            assertThat(interruptRestored).isTrue();
            assertThat(calls).hasValue(2);
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
            assertThat(inspector.inspect(privacyProcessedRequest()).failure()).isEqualTo(InspectionFailureCode.CANCELLED);
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
                new OpenAiCompatibleContentInspector("guard", config(true, Duration.ofSeconds(2), 4096), new JsonVerdictProtocol());
        assertThat(inspector.inspect(privacyProcessedRequest()).status())
                .isEqualTo(InspectionResult.Status.COMPLETED);
        response.set(envelope("{\"verdict\":\"UNSAFE\"}"));
        assertThat(inspector.inspect(privacyProcessedRequest()).findings()).hasSize(1);
        for (String content :
                List.of(
                        "{\"verdict\":\"SAFE\",\"reason\":\"extra\"}",
                        "SAFE",
                        "{\"verdict\":\"MAYBE\"}",
                        "```json\n{\"verdict\":\"SAFE\"}\n```",
                        "{\"verdict\":\"SAFE\"} {}",
                        "{\"verdict\":\"UNSAFE\",\"verdict\":\"SAFE\"}")) {
            response.set(envelope(content));
            assertThat(inspector.inspect(privacyProcessedRequest()).status())
                    .isEqualTo(InspectionResult.Status.FAILED);
        }
        response.set(envelope("{\"verdict\":\"SAFE\"}").replace("\"stop\"", "\"length\""));
        assertThat(inspector.inspect(privacyProcessedRequest()).status())
                .isEqualTo(InspectionResult.Status.FAILED);
    }

    @Test
    void configurationDoesNotExposeCredentials() throws Exception {
        start("<SAFE>");
        assertThat(config(true, Duration.ofSeconds(2), 4096).toString())
                .doesNotContain("test-key", endpoint.toString());
    }

    @Test
    void sameProtocolInstancesHaveDistinctIdentityAndPolicy() throws Exception {
        start("<UNSAFE-A1>");
        OpenAiCompatibleInspectionConfig config = config(true, Duration.ofSeconds(2), 4096);
        InspectionService service = new InspectionService(List.of(
                new OpenAiCompatibleContentInspector("observe", config, new KananaPromptProtocol()),
                new OpenAiCompatibleContentInspector("enforce", config, new KananaPromptProtocol())),
                (id, findings) -> "observe".equals(id) || findings.isEmpty()
                        ? InspectionDecision.ALLOW : InspectionDecision.BLOCK,
                InspectionFailurePolicy.FAIL_CLOSED);

        InspectionReport report = service.inspect(privacyProcessedRequest());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("observe", "enforce");
        assertThat(report.outcomes()).allSatisfy(outcome -> assertThat(outcome.result().findings()).hasSize(1));
        assertThat(calls).hasValue(2);
    }

    @Test
    void laterHttpFailurePreservesFindingAndCannotFailOpenPastContentBlock() throws Exception {
        startServer(exchange -> {
            try {
                byte[] bytes = (calls.get() == 1 ? envelope("<UNSAFE-A1>") : "{bad json")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector("guard", config(true, Duration.ofSeconds(2), 4096), new KananaPromptProtocol());
        InspectionService service = new InspectionService(List.of(inspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        InspectionReport report = service.inspect(twoSegments());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        InspectionResult result = report.outcomes().get(0).result();
        assertThat(result.failure()).isEqualTo(InspectionFailureCode.INVALID_RESPONSE);
        assertThat(result.completedSegmentIds()).containsExactly("first");
        assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("first");
        assertThat(calls).hasValue(2);
    }

    private InspectionRequest twoSegments() {
        return new InspectionRequest(List.of(
                new ContentSegment("first", ContentSegment.Role.USER,
                        ContentSegment.PrivacyProcessingStatus.PROCESSED, "synthetic attack"),
                new ContentSegment("second", ContentSegment.Role.TOOL,
                        ContentSegment.PrivacyProcessingStatus.PROCESSED, "synthetic tool result")),
                InspectionLimits.defaults());
    }

    @Test
    void findingLimitPreservesOnlyCompletedCoverageAndBoundedEvidence() throws Exception {
        OpenAiCompatibleContentInspector inspector = start("<UNSAFE-A1>");
        InspectionRequest request = new InspectionRequest(twoSegments().segments(),
                new InspectionLimits(2, 4096, 1, Duration.ofSeconds(10)));

        InspectionResult result = inspector.inspect(request);

        assertThat(result.failure()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
        assertThat(result.completedSegmentIds()).containsExactly("first");
        assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("first");
        assertThat(calls).hasValue(2);
    }

    @Test
    void customProtocolProvidesRequestAndVerdictWithoutChangingTransport() throws Exception {
        start("RISK");
        GuardModelProtocol protocol = new GuardModelProtocol() {
            public Map<String, Object> request(String model, String text) {
                return Map.of("model", model, "stream", false,
                        "messages", List.of(Map.of("role", "user", "content", "Classify: " + text)));
            }

            public InspectionFinding parse(String segmentId, String output) {
                assertThat(output).isEqualTo("RISK");
                return new InspectionFinding(segmentId, InspectionFinding.Category.PROMPT_ATTACK, "custom-risk", 0.9);
            }

            public boolean acceptsLengthFinish() {
                return false;
            }
        };
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "custom-protocol", config(true, Duration.ofSeconds(2), 4096), protocol);

        InspectionReport report = new InspectionService(List.of(inspector)).inspect(privacyProcessedRequest());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes().get(0).result().findings()).extracting(InspectionFinding::code)
                .containsExactly("custom-risk");
        JsonNode payload = JSON.readTree(received.get());
        assertThat(payload.path("model").asString()).isEqualTo("test-model");
        assertThat(payload.path("messages").get(0).path("content").asString()).startsWith("Classify: ");
        assertThat(payload.path("stream").asBoolean()).isFalse();
    }

    @Test
    void protocolResultConstructionFailureCannotFailOpenAsInvalidRemoteResponse() throws Exception {
        start("SAFE");
        GuardModelProtocol protocol = new GuardModelProtocol() {
            public Map<String, Object> request(String model, String text) {
                return new JsonVerdictProtocol().request(model, text);
            }

            public InspectionFinding parse(String segmentId, String output) {
                return new InspectionFinding(segmentId, InspectionFinding.Category.PROMPT_ATTACK, "invalid", Double.NaN);
            }

            public boolean acceptsLengthFinish() {
                return false;
            }
        };
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "invalid-protocol", config(true, Duration.ofSeconds(2), 4096), protocol);
        InspectionService service = new InspectionService(List.of(inspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        assertThatThrownBy(() -> service.inspect(privacyProcessedRequest()))
                .isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failure()).isEqualTo(InspectionFailureCode.INVALID_RESULT));
    }
}
