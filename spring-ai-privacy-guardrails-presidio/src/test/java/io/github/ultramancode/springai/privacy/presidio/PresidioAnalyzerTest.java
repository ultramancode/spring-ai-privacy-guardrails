package io.github.ultramancode.springai.privacy.presidio;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.ultramancode.springai.privacy.core.EntityTypeRegistry;
import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailure;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailureMetadata;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailurePolicy;
import io.github.ultramancode.springai.privacy.core.PiiResolutionPolicy;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import io.github.ultramancode.springai.privacy.core.PrivacyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import java.io.IOException;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PresidioAnalyzerTest {

    private static final TypeReference<Map<String, Object>> REQUEST_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() throws InterruptedException {
        if (this.server != null) {
            this.server.stop(0);
        }
        if (this.serverExecutor != null) {
            shutdownExecutor(this.serverExecutor);
            this.serverExecutor = null;
        }
    }

    @Test
    void analyzeMapsPresidioResponseToPiiSpans() throws IOException {
        startServer(200, """
                [{"entity_type":"PERSON","start":0,"end":5,"score":0.98}]
                """);
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        List<PiiSpan> spans = analyzer.analyze("Alice", PiiAnalysisOptions.defaults());

        assertThat(spans).containsExactly(new PiiSpan("PERSON", 0, 5, 0.98));
    }

    @Test
    void analyzeRejectsNonCanonicalProviderEntityLabels() throws IOException {
        startServer(200, """
                [{"entity_type":"person","start":0,"end":5,"score":0.98}]
                """);
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .isInstanceOfSatisfying(PresidioCallException.class, failure ->
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_RESPONSE_INVALID)
                );
    }

    @Test
    void analyzeInterpretsPresidioOffsetsAsCodePoints() throws IOException {
        startServer(200, """
                [{"entity_type":"PERSON","start":2,"end":7,"score":0.98}]
                """);
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        List<PiiSpan> spans = analyzer.analyze("🙂 Alice", PiiAnalysisOptions.defaults());

        assertThat(spans).containsExactly(new PiiSpan("PERSON", 3, 8, 0.98));
    }

    @Test
    void analyzeOmitsEntitiesWhenNoEntityFilterIsConfigured() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(200, "[]", requestBody);
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        analyzer.analyze("Alice", PiiAnalysisOptions.defaults());

        Map<String, Object> body = this.objectMapper.readValue(requestBody.get(), REQUEST_TYPE);
        assertThat(body)
                .containsEntry("text", "Alice")
                .containsEntry("language", "en")
                .doesNotContainKey("entities");
    }

    @Test
    void analyzeDoesNotSendCoreCanonicalEntityFiltersToPresidio() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(200, "[]", requestBody);
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        analyzer.analyze("123-45-6789", PiiAnalysisOptions.builder().includedEntityTypes(List.of("NATIONAL_ID")).build());

        Map<String, Object> body = this.objectMapper.readValue(requestBody.get(), REQUEST_TYPE);
        assertThat(body).doesNotContainKey("entities");
    }

    @Test
    void coreCanonicalizesAndPostFiltersPresidioNativeEntityTypes() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        startServer(200, """
                [{"entity_type":"US_SSN","start":0,"end":11,"score":0.98}]
                """, requestBody);
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());
        PrivacyService service = new PrivacyService(
                List.of(analyzer),
                PiiAnalysisOptions.builder().includedEntityTypes(List.of("NATIONAL_ID")).build(),
                new EntityTypeRegistry(Map.of("US_SSN", "NATIONAL_ID")),
                PiiResolutionPolicy.defaults()
        );

        var spans = service.analyze("123-45-6789");

        assertThat(spans).singleElement().satisfies(span -> {
            assertThat(span.entityType()).isEqualTo("NATIONAL_ID");
            assertThat(span.start()).isZero();
            assertThat(span.end()).isEqualTo(11);
        });
        Map<String, Object> body = this.objectMapper.readValue(requestBody.get(), REQUEST_TYPE);
        assertThat(body).doesNotContainKey("entities");
    }

    @Test
    void analyzeServerFailureRemainsVisibleToCorePolicy() throws IOException {
        startServer(500, "error");
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .hasMessageContaining("HTTP 500")
                .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_UNAVAILABLE);
                    assertThat(failure.attemptCount()).isEqualTo(1);
                });
    }

    @Test
    void analyzeTimeoutFailsClosedByDefault() throws IOException {
        startSlowServer();
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config(Duration.ofMillis(50)));

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .hasMessageContaining("timed out")
                .hasNoCause()
                .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_TIMEOUT);
                    assertThat(failure.attemptCount()).isEqualTo(1);
                });
    }

    @Test
    void analyzeTimeoutBoundsStalledResponseBodyAndReportsRetryCount() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch partialBodiesFlushed = new CountDownLatch(2);
        CountDownLatch releaseBodies = new CountDownLatch(1);
        startConcurrentServer(stalledJsonResponseBody(requests, partialBodiesFlushed, releaseBodies));
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config(Duration.ofMillis(500), 1));

        try {
            assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                    .hasMessage("Presidio analyzer call timed out")
                    .hasNoCause()
                    .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_TIMEOUT);
                        assertThat(failure.attemptCount()).isEqualTo(2);
                    });
            assertThat(requests).hasValue(2);
            assertThat(partialBodiesFlushed.getCount()).isZero();
        } finally {
            releaseBodies.countDown();
        }
    }

    @Test
    void analyzeInterruptionUsesInterruptedCategoryWithoutRetry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch partialBodyFlushed = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        startConcurrentServer(stalledJsonResponseBody(requests, partialBodyFlushed, releaseBody));
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config(Duration.ofSeconds(5), 2));
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread analysisThread = new Thread(() -> {
            try {
                analyzer.analyze("Alice", PiiAnalysisOptions.defaults());
            } catch (Throwable failure) {
                observedFailure.set(failure);
                interruptRestored.set(Thread.currentThread().isInterrupted());
            }
        });

        try {
            analysisThread.start();
            assertThat(partialBodyFlushed.await(5, TimeUnit.SECONDS)).isTrue();
            analysisThread.interrupt();
            analysisThread.join(2_000);

            assertThat(analysisThread.isAlive()).isFalse();
            assertThat(observedFailure.get())
                    .hasMessage("Presidio analyzer call interrupted")
                    .hasNoCause()
                    .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure -> {
                        assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYSIS_INTERRUPTED);
                        assertThat(failure.attemptCount()).isEqualTo(1);
                    });
            assertThat(interruptRestored).isTrue();
            assertThat(requests).hasValue(1);
        } finally {
            releaseBody.countDown();
            if (analysisThread.isAlive()) {
                analysisThread.interrupt();
                analysisThread.join(2_000);
            }
        }
    }

    @Test
    void analyzeDoesNotHideMalformedSuccessfulResponse() throws IOException {
        startServer(200, "not-json");
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .hasMessageContaining("parse")
                .hasMessageNotContaining("Alice")
                .hasNoCause()
                .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(PrivacyFailureCode.ANALYZER_RESPONSE_INVALID));
    }

    @Test
    void analyzeCancelsAnOversizedResponseBeforeUnboundedBuffering() throws IOException {
        startServer(200, "x".repeat(PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES + 1));
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .hasMessage("Presidio analyzer response exceeded the safe size limit")
                .hasMessageNotContaining("Alice")
                .hasNoCause()
                .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(PrivacyFailureCode.ANALYZER_RESPONSE_INVALID));
    }

    @Test
    void analyzeHonorsConfiguredResponseByteLimit() throws IOException {
        String response = "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":1,\"score\":1}]";
        int responseBytes = response.getBytes(StandardCharsets.UTF_8).length;
        startServer(200, response);

        PresidioAnalyzer acceptingAnalyzer = new PresidioAnalyzer(config(responseBytes));
        PresidioAnalyzer rejectingAnalyzer = new PresidioAnalyzer(config(responseBytes - 1));

        assertThat(acceptingAnalyzer.analyze("A", PiiAnalysisOptions.defaults()))
                .containsExactly(new PiiSpan("PERSON", 0, 1, 1.0));
        assertThatThrownBy(() -> rejectingAnalyzer.analyze("A", PiiAnalysisOptions.defaults()))
                .hasMessage("Presidio analyzer response exceeded the safe size limit")
                .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(PrivacyFailureCode.ANALYZER_RESPONSE_INVALID));
    }

    @Test
    void analyzeRejectsResponseCardinalityBeforeRetainingAnExtraSpan() throws IOException {
        String item = "{\"entity_type\":\"PERSON\",\"start\":0,\"end\":1,\"score\":1}";
        StringBuilder response = new StringBuilder("[");
        for (int index = 0; index <= PiiAnalyzer.MAX_RESULT_SPANS; index++) {
            if (index > 0) {
                response.append(',');
            }
            response.append(item);
        }
        response.append(']');
        assertThat(response.length()).isLessThan(PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES);
        startServer(200, response.toString());
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        assertThatThrownBy(() -> analyzer.analyze("A", PiiAnalysisOptions.defaults()))
                .hasMessageContaining("expected contract")
                .hasNoCause()
                .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(PrivacyFailureCode.ANALYZER_RESPONSE_INVALID));
    }

    @Test
    void analyzeRejectsDeepUnknownResponseMetadataWithinTheTransportLimit() throws IOException {
        StringBuilder nested = new StringBuilder("0");
        for (int index = 0; index < 65; index++) {
            nested.insert(0, "{\"nested\":").append('}');
        }
        startServer(200, "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":1,"
                + "\"score\":1,\"metadata\":" + nested + "}]");
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        assertThatThrownBy(() -> analyzer.analyze("A", PiiAnalysisOptions.defaults()))
                .hasMessageContaining("parse")
                .hasNoCause()
                .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo(PrivacyFailureCode.ANALYZER_RESPONSE_INVALID));
    }

    @Test
    void analyzeRejectsDuplicateContractFields() throws IOException {
        startServer(200, "[{\"entity_type\":\"PERSON\",\"entity_type\":\"EMAIL_ADDRESS\","
                + "\"start\":0,\"end\":1,\"score\":1}]");
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        assertThatThrownBy(() -> analyzer.analyze("A", PiiAnalysisOptions.defaults()))
                .hasMessageContaining("expected contract")
                .hasNoCause();
    }

    @Test
    void analyzeRejectsMalformedResponseItemsWithoutRetrying() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "[{\"entity_type\":\"PERSON\"}]");
        });
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config(3, Map.of()));

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .hasMessageContaining("expected contract")
                .hasMessageNotContaining("Alice");
        assertThat(requests).hasValue(1);
    }

    @Test
    void analyzeRequiresFiniteNumericScoreWithinUnitRange() throws IOException {
        List<String> invalidResponses = List.of(
                "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":5}]",
                "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":5,\"score\":null}]",
                "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":5,\"score\":\"0.9\"}]",
                "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":5,\"score\":-0.1}]",
                "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":5,\"score\":1.1}]",
                "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":5,\"score\":1e999}]"
        );
        AtomicInteger responseIndex = new AtomicInteger();
        startServer(exchange -> respond(exchange, 200, invalidResponses.get(responseIndex.getAndIncrement())));
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        for (String response : invalidResponses) {
            assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                    .as("response: %s", response)
                    .hasMessageContaining("expected contract")
                    .hasMessageNotContaining("Alice");
        }
        assertThat(responseIndex).hasValue(invalidResponses.size());
    }

    @Test
    void analyzeRequiresIntegralNumericOffsets() throws IOException {
        List<String> invalidResponses = List.of(
                "[{\"entity_type\":\"PERSON\",\"start\":0.0,\"end\":5,\"score\":0.9}]",
                "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":5.0,\"score\":0.9}]",
                "[{\"entity_type\":\"PERSON\",\"start\":\"0\",\"end\":5,\"score\":0.9}]",
                "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":\"5\",\"score\":0.9}]",
                "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":2147483648,\"score\":0.9}]"
        );
        AtomicInteger responseIndex = new AtomicInteger();
        startServer(exchange -> respond(exchange, 200, invalidResponses.get(responseIndex.getAndIncrement())));
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        for (String response : invalidResponses) {
            assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                    .as("response: %s", response)
                    .hasMessageContaining("expected contract")
                    .hasMessageNotContaining("Alice");
        }
        assertThat(responseIndex).hasValue(invalidResponses.size());
    }

    @Test
    void analyzeRejectsResponseSpansOutsideTheSourceText() throws IOException {
        startServer(200, "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":99,\"score\":0.9}]");
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .hasMessageContaining("expected contract")
                .hasMessageNotContaining("Alice");
    }

    @Test
    void analyzeRejectsNullInputsBeforeBlankShortCircuit() {
        PresidioAnalyzer analyzer = new PresidioAnalyzer("http://localhost:5002");

        assertThatThrownBy(() -> analyzer.analyze("", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("options must not be null");
        assertThatThrownBy(() -> analyzer.analyze(null, PiiAnalysisOptions.defaults()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("text must not be null");
    }

    @Test
    void configRejectsNullTransportSettings() {
        URI uri = URI.create("http://localhost:5002");

        assertThatThrownBy(() -> new PresidioAnalyzerConfig(
                uri, null, 0, Duration.ZERO, PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES, Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("timeout must not be null");
        assertThatThrownBy(() -> new PresidioAnalyzerConfig(
                uri, Duration.ofSeconds(1), 0, null, PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES, Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("retryBackoff must not be null");
        assertThatThrownBy(() -> new PresidioAnalyzerConfig(
                uri, Duration.ofSeconds(1), 0, Duration.ZERO, PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES, null
        )).isInstanceOf(NullPointerException.class)
                .hasMessage("headers must not be null");
    }

    @Test
    void configRejectsNonPositiveResponseByteLimit() {
        assertThatThrownBy(() -> new PresidioAnalyzerConfig(
                URI.create("http://localhost:5002"),
                Duration.ofSeconds(1),
                0,
                Duration.ZERO,
                0,
                Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxResponseBytes must be positive");
    }

    @Test
    void configRejectsInvalidHeaderSyntaxWithoutRenderingTheValue() {
        URI uri = URI.create("http://localhost:5002");

        assertThatThrownBy(() -> new PresidioAnalyzerConfig(
                uri,
                Duration.ofSeconds(1),
                0,
                Duration.ZERO,
                PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                Map.of("X-API-Key", "secret@example.com\nleak")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("header names and values must use valid HTTP syntax")
                .hasMessageNotContaining("secret@example.com");
        assertThatThrownBy(() -> new PresidioAnalyzerConfig(
                uri,
                Duration.ofSeconds(1),
                0,
                Duration.ZERO,
                PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                Map.of("Invalid Header", "safe")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("header names and values must use valid HTTP syntax");
        assertThatThrownBy(() -> new PresidioAnalyzerConfig(
                uri,
                Duration.ofSeconds(1),
                0,
                Duration.ZERO,
                PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                Map.of(" X-API-Key", "safe")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("header names and values must use valid HTTP syntax");
        assertThatThrownBy(() -> new PresidioAnalyzerConfig(
                uri,
                Duration.ofSeconds(1),
                0,
                Duration.ZERO,
                PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                Map.of("X-API-Key ", "safe")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("header names and values must use valid HTTP syntax");
        assertThatThrownBy(() -> new PresidioAnalyzerConfig(
                uri,
                Duration.ofSeconds(1),
                0,
                Duration.ZERO,
                PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                Map.of("X-API-Key", "first", "x-api-key", "second")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("header names must be unique ignoring case");
    }

    @Test
    void analyzeSanitizesJdkRejectionOfRestrictedConfiguredHeaders() {
        PresidioAnalyzer analyzer = new PresidioAnalyzer(new PresidioAnalyzerConfig(
                URI.create("http://localhost:5002"),
                Duration.ofSeconds(1),
                0,
                Duration.ZERO,
                PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                Map.of("Host", "secret@example.com")
        ));

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .hasMessage("Could not create Presidio analyzer request")
                .hasMessageNotContaining("secret@example.com")
                .hasNoCause();
    }

    @Test
    void configRejectsContentTypeOverrideInsteadOfSilentlyIgnoringIt() {
        assertThatThrownBy(() -> new PresidioAnalyzerConfig(
                URI.create("http://localhost:5002"),
                Duration.ofSeconds(1),
                0,
                Duration.ZERO,
                PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                Map.of("content-TYPE", "text/plain")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Content-Type is managed by the Presidio adapter");
    }

    @Test
    void analyzeDoesNotRetryClientErrors() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 422, "unsupported language");
        });
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config(3, Map.of()));

        assertThatThrownBy(() -> analyzer.analyze(
                "Alice",
                PiiAnalysisOptions.builder().language("ko").build()
        )).hasMessageContaining("HTTP 422");
        assertThat(requests).hasValue(1);
    }

    @Test
    void analyzeClassifiesHttpFailuresWithoutRetainingResponseContent() throws IOException {
        List<Integer> statuses = List.of(401, 408, 429, 422);
        List<PrivacyFailureCode> expectedCodes = List.of(
                PrivacyFailureCode.ANALYZER_AUTHENTICATION_FAILED,
                PrivacyFailureCode.ANALYZER_TIMEOUT,
                PrivacyFailureCode.ANALYZER_RATE_LIMITED,
                PrivacyFailureCode.ANALYZER_EXECUTION_FAILED
        );
        AtomicInteger responseIndex = new AtomicInteger();
        startServer(exchange -> respond(
                exchange,
                statuses.get(responseIndex.getAndIncrement()),
                "synthetic-secret-response"
        ));
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        for (int index = 0; index < statuses.size(); index++) {
            PrivacyFailureCode expectedCode = expectedCodes.get(index);
            assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                    .hasMessageNotContaining("Alice")
                    .hasMessageNotContaining("synthetic-secret-response")
                    .hasNoCause()
                    .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure -> {
                        assertThat(failure.code()).isEqualTo(expectedCode);
                        assertThat(failure.attemptCount()).isEqualTo(1);
                    });
        }
        assertThat(responseIndex).hasValue(statuses.size());
    }

    @Test
    void httpFailurePolicyRejectsNonErrorAndInvalidStatusCodes() {
        assertThatThrownBy(() -> PresidioAnalyzer.failureCodeForHttpErrorStatus(200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("status must represent an unsuccessful HTTP response");
        assertThatThrownBy(() -> PresidioAnalyzer.failureCodeForHttpErrorStatus(99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("status must be a three-digit HTTP status code");
        assertThatThrownBy(() -> PresidioAnalyzer.isRetryableHttpStatus(600))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("status must be a three-digit HTTP status code");
    }

    @Test
    void analyzeRetriesServerErrorsAndThenSucceeds() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            int request = requests.incrementAndGet();
            if (request == 1) {
                respond(exchange, 503, "unavailable");
                return;
            }
            respond(exchange, 200, "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":5,\"score\":0.9}]");
        });
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config(2, Map.of()));

        assertThat(analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .containsExactly(new PiiSpan("PERSON", 0, 5, 0.9));
        assertThat(requests).hasValue(2);
    }

    @Test
    void analyzeReportsActualAttemptCountWhenRetriesAreExhausted() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 503, "synthetic-secret-response");
        });
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config(2, Map.of()));

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .hasMessage("Presidio analyzer returned HTTP 503")
                .hasMessageNotContaining("Alice")
                .hasMessageNotContaining("synthetic-secret-response")
                .hasNoCause()
                .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_UNAVAILABLE);
                    assertThat(failure.attemptCount()).isEqualTo(3);
                });
        assertThat(requests).hasValue(3);
    }

    @Test
    void coreObserverReceivesPresidioFailureCategoryAndActualAttemptCount() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 503, "synthetic-secret-response");
        });
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config(2, Map.of()));
        PiiAnalyzer fallback = new PiiAnalyzer() {
            @Override
            public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
                return List.of();
            }

            @Override
            public String providerId() {
                return "FALLBACK";
            }
        };
        AtomicReference<PiiAnalyzerFailure> observed = new AtomicReference<>();
        PrivacyService service = new PrivacyService(
                List.of(analyzer, fallback),
                PiiAnalysisOptions.defaults(),
                EntityTypeRegistry.defaults(),
                PiiResolutionPolicy.builder()
                        .failurePolicy(PiiAnalyzerFailurePolicy.ALLOW_PARTIAL)
                        .build(),
                observed::set
        );

        var result = service.analyzeDetailed("Alice");

        PiiAnalyzerFailure expected = new PiiAnalyzerFailure(
                PresidioAnalyzer.PROVIDER_ID,
                PrivacyFailureCode.ANALYZER_UNAVAILABLE,
                PrivacyPhase.ANALYSIS,
                3
        );
        assertThat(result.failures()).containsExactly(expected);
        assertThat(observed).hasValue(expected);
        assertThat(requests).hasValue(3);
    }

    @Test
    void analyzeSendsConfiguredAuthenticationHeaders() throws IOException {
        AtomicReference<String> apiKey = new AtomicReference<>();
        startServer(exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            respond(exchange, 200, "[]");
        });
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config(
                0,
                Map.of("X-API-Key", "secret")
        ));

        analyzer.analyze("Alice", PiiAnalysisOptions.defaults());

        assertThat(apiKey).hasValue("secret");
    }

    @Test
    void configDoesNotRenderCredentialsAndRejectsCredentialBearingUrls() {
        PresidioAnalyzerConfig config = new PresidioAnalyzerConfig(
                URI.create("https://presidio.example.test"),
                Duration.ofSeconds(1),
                0,
                Duration.ZERO,
                PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                Map.of("Authorization", "Bearer synthetic-secret")
        );

        assertThat(config.toString())
                .contains(
                        "scheme=https",
                        "maxResponseBytes=" + PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                        "headerCount=1"
                )
                .doesNotContain("synthetic-secret", "Authorization", "presidio.example.test");
        assertThatThrownBy(() -> PresidioAnalyzerConfig.defaults(
                "https://user:synthetic-secret@presidio.example.test"
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not include user info");
        assertThatThrownBy(() -> PresidioAnalyzerConfig.defaults("https://[synthetic-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("synthetic-secret");
    }

    @Test
    void configDerivesAnalyzerAndHealthEndpointsFromOneValidatedBaseUri() {
        PresidioAnalyzerConfig config = new PresidioAnalyzerConfig(
                URI.create("https://presidio.example.test/base/"),
                Duration.ofSeconds(1),
                0,
                Duration.ZERO,
                PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                Map.of()
        );

        assertThat(config.analyzeEndpoint())
                .isEqualTo(URI.create("https://presidio.example.test/base/analyze"));
        assertThat(config.healthEndpoint())
                .isEqualTo(URI.create("https://presidio.example.test/base/health"));
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    void analyzeIsSafeToCallFromVirtualThreads() throws Exception {
        startServer(200, "[{\"entity_type\":\"PERSON\",\"start\":0,\"end\":5,\"score\":0.9}]");
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config());

        ExecutorService executor = virtualThreadExecutor();
        try {
            List<PiiSpan> result = executor.submit(
                    () -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults())
            ).get();
            assertThat(result).containsExactly(new PiiSpan("PERSON", 0, 5, 0.9));
        } finally {
            shutdownExecutor(executor);
        }
    }

    private static ExecutorService virtualThreadExecutor() throws ReflectiveOperationException {
        return (ExecutorService) Executors.class
                .getMethod("newVirtualThreadPerTaskExecutor")
                .invoke(null);
    }

    private PresidioAnalyzerConfig config() {
        return config(0, Map.of());
    }

    private PresidioAnalyzerConfig config(Duration timeout) {
        return config(timeout, 0);
    }

    private PresidioAnalyzerConfig config(Duration timeout, int maxRetries) {
        return new PresidioAnalyzerConfig(
                URI.create("http://localhost:" + this.server.getAddress().getPort()),
                timeout,
                maxRetries,
                Duration.ZERO,
                PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                Map.of()
        );
    }

    private PresidioAnalyzerConfig config(int maxResponseBytes) {
        return new PresidioAnalyzerConfig(
                URI.create("http://localhost:" + this.server.getAddress().getPort()),
                Duration.ofSeconds(5),
                0,
                Duration.ZERO,
                maxResponseBytes,
                Map.of()
        );
    }

    private PresidioAnalyzerConfig config(int maxRetries, Map<String, String> headers) {
        return new PresidioAnalyzerConfig(
                URI.create("http://localhost:" + this.server.getAddress().getPort()),
                Duration.ofSeconds(5),
                maxRetries,
                Duration.ZERO,
                PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                headers
        );
    }

    private void startServer(int status, String response) throws IOException {
        startServer(status, response, new AtomicReference<>());
    }

    private void startServer(int status, String response, AtomicReference<String> requestBody) throws IOException {
        startServer(exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, status, response);
        });
    }

    private void startServer(HttpHandler handler) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(0), 0);
        this.server.createContext("/analyze", handler);
        this.server.start();
    }

    private void startConcurrentServer(HttpHandler handler) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(0), 0);
        this.serverExecutor = Executors.newFixedThreadPool(2);
        this.server.setExecutor(this.serverExecutor);
        this.server.createContext("/analyze", handler);
        this.server.start();
    }

    private static HttpHandler stalledJsonResponseBody(
            AtomicInteger requests,
            CountDownLatch partialBodiesFlushed,
            CountDownLatch releaseBodies
    ) {
        return exchange -> {
            requests.incrementAndGet();
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try {
                exchange.getResponseBody().write(body, 0, 1);
                exchange.getResponseBody().flush();
                partialBodiesFlushed.countDown();
                releaseBodies.await(2, TimeUnit.SECONDS);
                exchange.getResponseBody().write(body, 1, 1);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // A client-side cancellation request may close the exchange before the final byte is written.
            } finally {
                exchange.close();
            }
        };
    }

    private void startSlowServer() throws IOException {
        startServer(exchange -> {
            try {
                Thread.sleep(250);
                respond(exchange, 200, "[]");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
            } catch (IOException ignored) {
                exchange.close();
            }
        });
    }

    private void respond(HttpExchange exchange, int status, String response) throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void shutdownExecutor(ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
}
