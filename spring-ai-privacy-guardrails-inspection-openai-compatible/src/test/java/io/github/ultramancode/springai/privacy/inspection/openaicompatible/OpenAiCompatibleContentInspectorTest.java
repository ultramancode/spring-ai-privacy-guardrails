package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
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
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol.GuardModelProtocol;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol.JsonVerdictProtocol;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol.KananaSafeguardPromptProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleContentInspectorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> receivedRequestBody = new AtomicReference<>();
    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private int responseStatus = 200;
    private URI baseUrl;

    @AfterEach
    void close() {
        if (server != null) {
            server.stop(0);
        }
        executor.shutdownNow();
    }

    private void startServer(String output) throws Exception {
        responseBody.set(envelope(output));
        startServer(exchange -> {
            try {
                byte[] bytes = responseBody.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
    }

    private OpenAiCompatibleContentInspector kananaInspector() {
        return new OpenAiCompatibleContentInspector(
                "guard", config(true, Duration.ofSeconds(2), 4096), new KananaSafeguardPromptProtocol());
    }

    private void startServer(HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            receivedRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            handler.handle(exchange);
        });
        server.setExecutor(executor);
        server.start();
        baseUrl =
                URI.create(
                        "http://127.0.0.1:"
                                + server.getAddress().getPort()
                                + "/v1");
    }

    private OpenAiCompatibleInspectionConfig config(
            boolean requirePrivacyProcessedContent, Duration timeout, int maxResponseBytes) {
        return new OpenAiCompatibleInspectionConfig(
                baseUrl, "test-model", "test-key", timeout, maxResponseBytes, requirePrivacyProcessedContent);
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

    private static String envelope(String content) throws JsonProcessingException {
        return envelope(content, "stop");
    }

    private static String envelope(String content, String finishReason) throws JsonProcessingException {
        Map<String, Object> choice = new HashMap<>();
        choice.put("message", Map.of("role", "assistant", "content", content));
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        }
        return JSON.writeValueAsString(Map.of("choices", List.of(choice)));
    }

    private void waitUntilDeadlineExpires(InspectionRequest request) {
        while (true) {
            try {
                LockSupport.parkNanos(request.remaining().toNanos());
            } catch (InspectionException expired) {
                assertThat(expired.failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
                return;
            }
        }
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
    void baseUrlQueryIsPreservedInTheHttpRequest() throws Exception {
        AtomicReference<String> receivedQuery = new AtomicReference<>();
        startServer(exchange -> {
            receivedQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = envelope("<SAFE>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        String query = "api-version=2026-01-01&route=guard%2Fprimary";
        baseUrl = URI.create(baseUrl + "?" + query);
        OpenAiCompatibleContentInspector inspector = kananaInspector();

        InspectionResult result = inspector.inspect(privacyProcessedRequest());

        assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(receivedQuery).hasValue(query);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void usesPlaceholderAuthorizationWhenNoApiKeyIsConfigured(String apiKey) throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = envelope("<SAFE>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        OpenAiCompatibleInspectionConfig config = new OpenAiCompatibleInspectionConfig(
                baseUrl, "test-model", apiKey, Duration.ofSeconds(2), 4096, true);
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config, new KananaSafeguardPromptProtocol());

        InspectionResult result = inspector.inspect(privacyProcessedRequest());

        assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(authorization).hasValue("Bearer not-required");
        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotFollowRedirects() throws Exception {
        AtomicInteger redirectedCalls = new AtomicInteger();
        startServer(exchange -> {
            exchange.getResponseHeaders().set("Location", "/redirected");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            redirectedCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        OpenAiCompatibleContentInspector inspector = kananaInspector();

        InspectionResult result = inspector.inspect(privacyProcessedRequest());

        assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.HTTP_ERROR);
        assertThat(redirectedCalls).hasValue(0);
        assertThat(calls).hasValue(1);
    }

    @Test
    void inspectionDeadlineAlsoLimitsResponseBodyReads() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        startServer(exchange -> {
            try {
                byte[] bytes = envelope("<SAFE>").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes, 0, 1);
                exchange.getResponseBody().flush();
                bodyStarted.countDown();
                releaseBody.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config(true, Duration.ofSeconds(10), 4096), new KananaSafeguardPromptProtocol());
        InspectionRequest request = new InspectionRequest(privacyProcessedRequest().segments(),
                new InspectionLimits(16, 10000, Duration.ofMillis(500)));
        try {
            InspectionResult result = inspector.inspect(request);

            assertThat(bodyStarted.getCount()).isZero();
            assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
            assertThat(result.completedSegmentIds()).isEmpty();
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void mapsKananaVerdictsFromHttpResponses() throws Exception {
        startServer("<SAFE>");
        OpenAiCompatibleContentInspector inspector = kananaInspector();
        InspectionResult safe = inspector.inspect(privacyProcessedRequest());
        assertThat(safe.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(safe.completedSegmentIds()).containsExactly("s1");
        assertThat(safe.findings()).isEmpty();
        responseBody.set(envelope("<UNSAFE-A1>"));
        assertThat(inspector.inspect(privacyProcessedRequest()).findings())
                .singleElement()
                .extracting(InspectionFinding::category)
                .isEqualTo(InspectionFinding.Category.PROMPT_INJECTION);
    }

    @ParameterizedTest
    @EnumSource(value = ContentSegment.PrivacyProcessingStatus.class, names = {"UNKNOWN", "UNPROCESSED"})
    void requiredPrivacyProcessingRejectsUnknownAndUnprocessedContentBeforeHttp(
            ContentSegment.PrivacyProcessingStatus status) throws Exception {
        startServer("<SAFE>");
        OpenAiCompatibleContentInspector inspector = kananaInspector();
        InspectionRequest request = request(status, "raw secret");

        assertThatThrownBy(() -> inspector.inspect(request))
                .isInstanceOfSatisfying(InspectionException.class, failure ->
                        assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.PRIVACY_PROCESSING_REQUIRED));
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(value = ContentSegment.PrivacyProcessingStatus.class, names = {"UNKNOWN", "UNPROCESSED"})
    void disabledPrivacyRequirementAllowsStandaloneHttpInspection(ContentSegment.PrivacyProcessingStatus status) throws Exception {
        startServer("<SAFE>");
        OpenAiCompatibleContentInspector inspector =
                new OpenAiCompatibleContentInspector("guard", config(false, Duration.ofSeconds(2), 4096), new KananaSafeguardPromptProtocol());
        InspectionService service = new InspectionService(List.of(inspector));

        InspectionReport report = service.inspect(request(status, "synthetic raw"));

        assertThat(report.decision()).isEqualTo(InspectionDecision.ALLOW);
        assertThat(report.outcomes().get(0).result().status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(receivedRequestBody.get()).contains("synthetic raw");
    }

    @Test
    void acceptsResponseAtTheByteLimitAndRejectsOneByteMore() throws Exception {
        startServer("<SAFE>");
        int maxResponseBytes = 128;
        String body = envelope("<SAFE>");
        String bodyAtLimit = body + " ".repeat(maxResponseBytes - body.getBytes(StandardCharsets.UTF_8).length);
        responseBody.set(bodyAtLimit);
        OpenAiCompatibleInspectionConfig config = config(true, Duration.ofSeconds(2), maxResponseBytes);
        OpenAiCompatibleContentInspector inspector =
                new OpenAiCompatibleContentInspector("guard", config, new KananaSafeguardPromptProtocol());

        InspectionResult accepted = inspector.inspect(privacyProcessedRequest());

        assertThat(accepted.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        responseBody.set(bodyAtLimit + " ");

        InspectionResult rejected = inspector.inspect(privacyProcessedRequest());

        assertThat(rejected.failureCode()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
        assertThat(rejected.completedSegmentIds()).isEmpty();
    }

    @Test
    void sendsConfiguredModelAndApiKey() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        startServer(exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = envelope("<SAFE>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        String model = "deployed-guard";
        String key = "configured-api-key";
        OpenAiCompatibleInspectionConfig configuration = new OpenAiCompatibleInspectionConfig(
                baseUrl, model, key, Duration.ofSeconds(2), 4096, true);
        OpenAiCompatibleContentInspector inspector =
                new OpenAiCompatibleContentInspector("guard", configuration, new KananaSafeguardPromptProtocol());

        InspectionResult result = inspector.inspect(privacyProcessedRequest());

        assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);

        JsonNode requestBody = JSON.readTree(receivedRequestBody.get());
        assertThat(requestBody.get("model").asText()).isEqualTo(model);
        assertThat(authorization.get()).isEqualTo("Bearer " + key);
    }

    @Test
    void malformedApiKeyFailsBeforeHttpEvenWithFailOpen() throws Exception {
        startServer("<SAFE>");
        OpenAiCompatibleInspectionConfig config = new OpenAiCompatibleInspectionConfig(
                baseUrl, "test-model", "key\r\ninjected", Duration.ofSeconds(2), 4096, true);
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config, new KananaSafeguardPromptProtocol());
        InspectionService service = new InspectionService(List.of(inspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        assertThatThrownBy(() -> service.inspect(privacyProcessedRequest()))
                .isInstanceOfSatisfying(InspectionException.class, failure ->
                        assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT));
        assertThat(calls).hasValue(0);
    }

    @Test
    void invalidHttpResponsesNeverBecomeSafe() throws Exception {
        startServer("<SAFE>");
        OpenAiCompatibleContentInspector inspector = kananaInspector();
        List<String> invalidResponses = List.of(
                envelope(""),
                "{bad json",
                "{}",
                "null",
                "{\"choices\":[null]}",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":false}}]}",
                "{\"choices\":[],\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"<SAFE>\"}}]}",
                "{\"choices\":[]}",
                """
                {"choices":[
                    {"message":{"role":"assistant","content":"<SAFE>"}},
                    {"message":{"role":"assistant","content":"<UNSAFE-A1>"}}
                ]}
                """,
                envelope("<SAFE>") + "{}",
                """
                {"choices":[{"message":{"role":"assistant","content":"<SAFE>","tool_calls":[{}]}}]}
                """,
                """
                {"choices":[{"message":{"role":"assistant","content":"<SAFE>","function_call":{"name":"lookup","arguments":"{}"}}}]}
                """,
                """
                {"choices":[{"message":{"role":"assistant","content":"<SAFE>","refusal":"Cannot classify"}}]}
                """);

        for (String body : invalidResponses) {
            responseBody.set(body);
            InspectionService service = new InspectionService(List.of(inspector));

            InspectionReport report = service.inspect(privacyProcessedRequest());

            assertThat(report.decision()).as("HTTP response: %s", body).isEqualTo(InspectionDecision.BLOCK);
            InspectionResult result = report.outcomes().get(0).result();
            assertThat(result.status()).as("HTTP response: %s", body).isEqualTo(InspectionResult.Status.FAILED);
            assertThat(result.failureCode()).as("HTTP response: %s", body)
                    .isEqualTo(InspectionFailureCode.INVALID_RESPONSE);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"stop", "length", "tool_calls", "content_filter", "vendor_specific"})
    void acceptsCompleteVerdictsRegardlessOfFinishReason(String finishReason) throws Exception {
        startServer("<SAFE>");
        OpenAiCompatibleContentInspector inspector = kananaInspector();
        responseBody.set(envelope("<SAFE>", finishReason));

        InspectionResult result = inspector.inspect(privacyProcessedRequest());

        assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(result.completedSegmentIds()).containsExactly("s1");
        assertThat(result.findings()).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    void acceptsCompleteVerdictWithNullOrEmptyRefusal(String refusal) throws Exception {
        startServer("<SAFE>");
        OpenAiCompatibleContentInspector inspector = kananaInspector();
        Map<String, Object> message = new HashMap<>();
        message.put("role", "assistant");
        message.put("content", "<SAFE>");
        message.put("refusal", refusal);
        responseBody.set(JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", message)))));

        InspectionResult result = inspector.inspect(privacyProcessedRequest());

        assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(result.completedSegmentIds()).containsExactly("s1");
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void rejectsNonStringRefusalEvenWithValidVerdict() throws Exception {
        startServer("<SAFE>");
        OpenAiCompatibleContentInspector inspector = kananaInspector();
        for (Object refusal : List.of(0, false, List.of(), Map.of())) {
            Map<String, Object> message =
                    Map.of("role", "assistant", "content", "<SAFE>", "refusal", refusal);
            responseBody.set(JSON.writeValueAsString(Map.of("choices", List.of(Map.of("message", message)))));

            InspectionResult result = inspector.inspect(privacyProcessedRequest());

            assertThat(result.failureCode()).as("refusal: %s", refusal)
                    .isEqualTo(InspectionFailureCode.INVALID_RESPONSE);
            assertThat(result.completedSegmentIds()).as("refusal: %s", refusal).isEmpty();
            assertThat(result.findings()).as("refusal: %s", refusal).isEmpty();
        }
    }

    @Test
    void boundedResponsesAndHttpFailuresAreSanitized() throws Exception {
        startServer("<SAFE>");
        OpenAiCompatibleContentInspector inspector =
                new OpenAiCompatibleContentInspector("guard", config(true, Duration.ofSeconds(2), 128), new KananaSafeguardPromptProtocol());
        responseBody.set(envelope("secret".repeat(300)));
        InspectionResult limitFailure = inspector.inspect(privacyProcessedRequest());
        assertThat(limitFailure.failureCode()).isEqualTo(InspectionFailureCode.LIMIT_EXCEEDED);
        assertThat(limitFailure.toString()).doesNotContain("secret");

        responseStatus = 503;
        responseBody.set("raw failure details");
        InspectionResult httpFailure = inspector.inspect(privacyProcessedRequest());
        assertThat(httpFailure.failureCode()).isEqualTo(InspectionFailureCode.HTTP_ERROR);
        assertThat(httpFailure.toString()).doesNotContain("raw failure details");
        assertThat(calls).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 503})
    void limitsResponseReadsWithoutWaitingForTheBodyToFinish(int httpStatus) throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        startServer(exchange -> {
            try {
                exchange.sendResponseHeaders(httpStatus, 0);
                exchange.getResponseBody().write(" ".repeat(1024).getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                releaseBody.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config(true, Duration.ofSeconds(5), 128), new KananaSafeguardPromptProtocol());
        try {
            CompletableFuture<InspectionResult> pending = CompletableFuture.supplyAsync(
                    () -> inspector.inspect(privacyProcessedRequest()), executor);
            InspectionResult result = pending.get(2, TimeUnit.SECONDS);

            InspectionFailureCode expected = InspectionFailureCode.LIMIT_EXCEEDED;
            if (httpStatus != 200) {
                expected = InspectionFailureCode.HTTP_ERROR;
            }
            assertThat(result.failureCode()).isEqualTo(expected);
            assertThat(result.completedSegmentIds()).isEmpty();
            assertThat(calls).hasValue(1);
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void rejectsMalformedToolCallsEvenWhenTheNodeIsEmpty() throws Exception {
        startServer("<SAFE>");
        OpenAiCompatibleContentInspector inspector = kananaInspector();
        for (Object malformedToolCalls : List.of("", "unexpected", Map.of(), 0, false)) {
            Map<String, Object> message =
                    Map.of("role", "assistant", "content", "<SAFE>", "tool_calls", malformedToolCalls);
            Map<String, Object> choice = Map.of("finish_reason", "stop", "message", message);
            responseBody.set(JSON.writeValueAsString(Map.of("choices", List.of(choice))));

            InspectionResult result = inspector.inspect(privacyProcessedRequest());

            assertThat(result.failureCode()).as("tool_calls: %s", malformedToolCalls)
                    .isEqualTo(InspectionFailureCode.INVALID_RESPONSE);
        }
    }

    @Test
    void timeoutIsAnOperationalFailure() throws Exception {
        startServer(exchange -> {
            try {
                Thread.sleep(500);
                byte[] body = envelope("<SAFE>").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        OpenAiCompatibleContentInspector inspector =
                new OpenAiCompatibleContentInspector("guard", config(true, Duration.ofMillis(50), 4096), new KananaSafeguardPromptProtocol());
        assertThat(inspector.inspect(privacyProcessedRequest()).failureCode())
                .isEqualTo(InspectionFailureCode.TIMEOUT);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedParsingAfterDeadlineRetainsEvidenceAndStopsFurtherHttp(boolean moreSegments) throws Exception {
        startServer("<UNSAFE-A1>");
        GuardModelProtocol delegate = new KananaSafeguardPromptProtocol();
        AtomicReference<InspectionRequest> activeRequest = new AtomicReference<>();
        GuardModelProtocol slowProtocol = new GuardModelProtocol() {
            @Override
            public ChatCompletionCreateParams request(String model, String text) {
                return delegate.request(model, text);
            }

            @Override
            public Optional<InspectionFinding> parse(String segmentId, String output) {
                waitUntilDeadlineExpires(activeRequest.get());
                return delegate.parse(segmentId, output);
            }
        };
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config(true, Duration.ofSeconds(2), 4096), slowProtocol);
        List<ContentSegment> segments = privacyProcessedRequest().segments();
        if (moreSegments) {
            segments = twoSegments().segments();
        }
        InspectionRequest request = new InspectionRequest(segments,
                new InspectionLimits(2, 1000, Duration.ofSeconds(2)));
        activeRequest.set(request);

        InspectionResult result = inspector.inspect(request);

        assertThat(calls).hasValue(1);
        assertThat(result.completedSegmentIds()).containsExactly(segments.get(0).id());
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.segmentId()).isEqualTo(request.segments().get(0).id());
            assertThat(finding.category()).isEqualTo(InspectionFinding.Category.PROMPT_INJECTION);
        });
        if (moreSegments) {
            assertThat(result.status()).isEqualTo(InspectionResult.Status.FAILED);
            assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.TIMEOUT);
        } else {
            assertThat(result.status()).isEqualTo(InspectionResult.Status.COMPLETED);
            assertThat(result.failureCode()).isNull();
        }
    }

    @Test
    void interruptedProtocolFailurePreservesItsCodeAndPartialEvidence() throws Exception {
        startServer("<UNSAFE-A1>");
        GuardModelProtocol delegate = new KananaSafeguardPromptProtocol();
        GuardModelProtocol failingProtocol = new GuardModelProtocol() {
            @Override
            public ChatCompletionCreateParams request(String model, String text) {
                return delegate.request(model, text);
            }

            @Override
            public Optional<InspectionFinding> parse(String segmentId, String output) {
                if (segmentId.equals("second")) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("private raw text");
                }
                return delegate.parse(segmentId, output);
            }
        };
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config(true, Duration.ofSeconds(2), 4096), failingProtocol);
        InspectionService service = new InspectionService(List.of(inspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        try {
            assertThatThrownBy(() -> service.inspect(twoSegments()))
                    .isInstanceOfSatisfying(InspectionException.class, serviceFailure -> {
                        assertThat(serviceFailure.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);

                        InspectionReport report = serviceFailure.report().orElseThrow();
                        InspectionResult inspectorResult = report.outcomes().get(0).result();
                        assertThat(inspectorResult.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT);
                        assertThat(inspectorResult.completedSegmentIds()).containsExactly("first");
                        assertThat(inspectorResult.findings()).extracting(InspectionFinding::segmentId)
                                .containsExactly("first");
                    }).hasNoCause().hasMessageNotContaining("private raw text");
            assertThat(calls).hasValue(2);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptionWhileAwaitingResponsePreservesPartialEvidence(boolean beforeHeaders) throws Exception {
        CountDownLatch responsePaused = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        startServer(exchange -> {
            try {
                if (calls.get() == 1) {
                    byte[] bytes = envelope("<UNSAFE-A1>").getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    return;
                }
                if (!beforeHeaders) {
                    byte[] bytes = envelope("<SAFE>").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes, 0, 1);
                    exchange.getResponseBody().flush();
                }
                responsePaused.countDown();
                releaseResponse.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config(true, Duration.ofSeconds(10), 4096), new KananaSafeguardPromptProtocol());
        FutureTask<InspectionResult> pending = new FutureTask<>(() -> {
            InspectionResult result = inspector.inspect(twoSegments());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            return result;
        });
        Thread worker = new Thread(pending);
        try {
            worker.start();
            assertThat(responsePaused.await(5, TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            InspectionResult result = pending.get(2, TimeUnit.SECONDS);

            assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);
            assertThat(result.completedSegmentIds()).containsExactly("first");
            assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("first");
            assertThat(calls).hasValue(2);
        } finally {
            releaseResponse.countDown();
            if (worker.isAlive()) {
                worker.interrupt();
                worker.join(2_000);
            }
            assertThat(worker.isAlive()).isFalse();
        }
    }

    @Test
    void interruptedRequestNeverStartsHttp() throws Exception {
        startServer("<SAFE>");
        OpenAiCompatibleContentInspector inspector = kananaInspector();
        try {
            Thread.currentThread().interrupt();
            assertThat(inspector.inspect(privacyProcessedRequest()).failureCode()).isEqualTo(InspectionFailureCode.CANCELLED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(calls).hasValue(0);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void mapsJsonVerdictsAndParsingFailuresFromHttpResponses() throws Exception {
        startServer("{\"verdict\":\"SAFE\"}");
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config(true, Duration.ofSeconds(2), 4096), new JsonVerdictProtocol());

        InspectionResult safe = inspector.inspect(privacyProcessedRequest());

        assertThat(safe.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(safe.completedSegmentIds()).containsExactly("s1");
        assertThat(safe.findings()).isEmpty();
        responseBody.set(envelope("{\"verdict\":\"UNSAFE\"}"));
        InspectionResult unsafe = inspector.inspect(privacyProcessedRequest());
        assertThat(unsafe.status()).isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(unsafe.findings()).singleElement()
                .extracting(InspectionFinding::category)
                .isEqualTo(InspectionFinding.Category.PROMPT_ATTACK);
        responseBody.set(envelope("{\"verdict\":\"MAYBE\"}"));
        InspectionResult invalid = inspector.inspect(privacyProcessedRequest());
        assertThat(invalid.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESPONSE);
        assertThat(invalid.completedSegmentIds()).isEmpty();
        assertThat(invalid.findings()).isEmpty();
    }

    @Test
    void inspectorsUsingTheSameProtocolHaveDistinctIdentityAndPolicy() throws Exception {
        startServer("<UNSAFE-A1>");
        OpenAiCompatibleInspectionConfig config = config(true, Duration.ofSeconds(2), 4096);
        InspectionService service = new InspectionService(List.of(
                new OpenAiCompatibleContentInspector("observe", config, new KananaSafeguardPromptProtocol()),
                new OpenAiCompatibleContentInspector("enforce", config, new KananaSafeguardPromptProtocol())),
                (id, findings) -> {
                    if ("observe".equals(id) || findings.isEmpty()) {
                        return InspectionDecision.ALLOW;
                    }
                    return InspectionDecision.BLOCK;
                },
                InspectionFailurePolicy.FAIL_CLOSED);

        InspectionReport report = service.inspect(privacyProcessedRequest());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes()).extracting(InspectionReport.Outcome::inspectorId)
                .containsExactly("observe", "enforce");
        assertThat(report.outcomes()).allSatisfy(outcome -> {
            assertThat(outcome.result().findings()).hasSize(1);
        });
        assertThat(calls).hasValue(2);
    }

    @Test
    void invalidLaterResponseRetainsEarlierFindingsWithFailOpen() throws Exception {
        startServer(exchange -> {
            try {
                String body = envelope("<UNSAFE-A1>");
                if (calls.get() != 1) {
                    body = "{bad json";
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        OpenAiCompatibleContentInspector inspector = kananaInspector();
        InspectionService service = new InspectionService(List.of(inspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        InspectionReport report = service.inspect(twoSegments());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        InspectionResult result = report.outcomes().get(0).result();
        assertThat(result.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESPONSE);
        assertThat(result.completedSegmentIds()).containsExactly("first");
        assertThat(result.findings()).extracting(InspectionFinding::segmentId).containsExactly("first");
        assertThat(calls).hasValue(2);
    }

    @Test
    void usesCustomProtocolWithNonStreamingRequests() throws Exception {
        startServer("RISK");
        GuardModelProtocol protocol = new GuardModelProtocol() {
            @Override
            public ChatCompletionCreateParams request(String model, String text) {
                return ChatCompletionCreateParams.builder()
                        .model(model).addUserMessage("Classify: " + text)
                        .putAdditionalBodyProperty("stream", JsonValue.from(true))
                        .build();
            }

            @Override
            public Optional<InspectionFinding> parse(String segmentId, String output) {
                assertThat(output).isEqualTo("RISK");
                return Optional.of(new InspectionFinding(
                        segmentId, InspectionFinding.Category.PROMPT_ATTACK, "custom-risk", 0.9));
            }
        };
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "custom-protocol", config(true, Duration.ofSeconds(2), 4096), protocol);

        InspectionReport report = new InspectionService(List.of(inspector)).inspect(privacyProcessedRequest());

        assertThat(report.decision()).isEqualTo(InspectionDecision.BLOCK);
        assertThat(report.outcomes().get(0).result().findings()).extracting(InspectionFinding::code)
                .containsExactly("custom-risk");
        JsonNode payload = JSON.readTree(receivedRequestBody.get());
        assertThat(payload.path("model").asText()).isEqualTo("test-model");
        assertThat(payload.path("messages").get(0).path("content").asText()).startsWith("Classify: ");
        assertThat(payload.path("stream").isBoolean()).isTrue();
        assertThat(payload.path("stream").booleanValue()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 2})
    void unsupportedChoiceCountFailsBeforeHttpEvenWithFailOpen(long choiceCount) throws Exception {
        startServer("<SAFE>");
        GuardModelProtocol delegate = new KananaSafeguardPromptProtocol();
        GuardModelProtocol protocol = new GuardModelProtocol() {
            @Override
            public ChatCompletionCreateParams request(String model, String text) {
                return delegate.request(model, text).toBuilder().n(choiceCount).build();
            }

            @Override
            public Optional<InspectionFinding> parse(String segmentId, String output) {
                return delegate.parse(segmentId, output);
            }
        };
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "guard", config(true, Duration.ofSeconds(2), 4096), protocol);
        InspectionService service = new InspectionService(List.of(inspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        assertThatThrownBy(() -> service.inspect(privacyProcessedRequest()))
                .isInstanceOfSatisfying(InspectionException.class, failure ->
                        assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT));
        assertThat(calls).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void protocolContractViolationsCannotFailOpen(boolean returnsNull) throws Exception {
        startServer("SAFE");
        GuardModelProtocol protocol = new GuardModelProtocol() {
            @Override
            public ChatCompletionCreateParams request(String model, String text) {
                return new JsonVerdictProtocol().request(model, text);
            }

            @Override
            public Optional<InspectionFinding> parse(String segmentId, String output) {
                if (returnsNull) {
                    return null;
                }
                return Optional.of(new InspectionFinding(
                        segmentId, InspectionFinding.Category.PROMPT_ATTACK, "invalid", Double.NaN));
            }
        };
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "invalid-protocol", config(true, Duration.ofSeconds(2), 4096), protocol);
        InspectionService service = new InspectionService(List.of(inspector),
                InspectionPolicy.blockFindings(), InspectionFailurePolicy.FAIL_OPEN);

        assertThatThrownBy(() -> service.inspect(privacyProcessedRequest()))
                .isInstanceOfSatisfying(InspectionException.class,
                        failure -> assertThat(failure.failureCode()).isEqualTo(InspectionFailureCode.INVALID_RESULT));
    }
}
