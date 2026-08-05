package io.github.ultramancode.springai.privacy.presidio.autoconfigure;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.ultramancode.springai.privacy.autoconfigure.PrivacyGuardrailsAutoConfiguration;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.presidio.PresidioAnalyzer;
import io.github.ultramancode.springai.privacy.presidio.PresidioAnalyzerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PresidioPrivacyGuardrailsAutoConfigurationTest {

    private HttpServer server;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PresidioPrivacyGuardrailsAutoConfiguration.class,
                    PrivacyGuardrailsAutoConfiguration.class
            ))
            .withPropertyValues("spring.ai.privacy.enabled=true");

    @AfterEach
    void stopServer() {
        if (this.server != null) {
            this.server.stop(0);
        }
    }

    @Test
    void autoConfigurationDoesNotCreatePresidioAnalyzerByDefault() {
        this.contextRunner
                .withBean(PiiAnalyzer.class, () -> (text, options) -> List.of())
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PresidioAnalyzer.class)
                        .doesNotHaveBean(PresidioAnalyzerConfig.class)
                        .doesNotHaveBean("presidioHealthIndicator"));
    }

    @Test
    void autoConfigurationRequiresGlobalOptInEvenWhenProviderIsEnabled() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PresidioPrivacyGuardrailsAutoConfiguration.class,
                        PrivacyGuardrailsAutoConfiguration.class
                ))
                .withPropertyValues("spring.ai.privacy.presidio.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(PresidioAnalyzer.class)
                        .doesNotHaveBean(PresidioAnalyzerConfig.class));
    }

    @Test
    void autoConfigurationCreatesPresidioAnalyzerWhenEnabled() {
        this.contextRunner
                .withPropertyValues(
                        "spring.ai.privacy.presidio.enabled=true",
                        "spring.ai.privacy.presidio.analyzer-url=http://localhost:5002",
                        "spring.ai.privacy.presidio.timeout=2s",
                        "spring.ai.privacy.presidio.max-retries=3",
                        "spring.ai.privacy.presidio.max-response-bytes=4096"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PresidioAnalyzer.class);
                    assertThat(context).hasSingleBean(PresidioAnalyzerConfig.class);
                    assertThat(context).hasBean("presidioHealthIndicator");
                    assertThat(context.getBean(PresidioAnalyzer.class).providerId())
                            .isEqualTo(PresidioAnalyzer.PROVIDER_ID);

                    PresidioPrivacyGuardrailsProperties properties = context.getBean(
                            PresidioPrivacyGuardrailsProperties.class
                    );
                    assertThat(properties.getAnalyzerUrl()).isEqualTo(URI.create("http://localhost:5002"));
                    assertThat(properties.getTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(properties.getMaxRetries()).isEqualTo(3);
                    assertThat(properties.getMaxResponseBytes()).isEqualTo(4096);
                    assertThat(context.getBean(PresidioAnalyzerConfig.class).maxResponseBytes())
                            .isEqualTo(4096);
                });
    }

    @Test
    void customAnalyzerBacksOffPropertyDerivedConfigAndHealthIndicator() {
        PresidioAnalyzer customAnalyzer = new PresidioAnalyzer("http://custom.example:5002");

        this.contextRunner
                .withBean(PresidioAnalyzer.class, () -> customAnalyzer)
                .withPropertyValues(
                        "spring.ai.privacy.presidio.enabled=true",
                        "spring.ai.privacy.presidio.analyzer-url=http://wrong.example:5002"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(PresidioAnalyzer.class);
                    assertThat(context.getBean(PresidioAnalyzer.class)).isSameAs(customAnalyzer);
                    assertThat(context)
                            .doesNotHaveBean(PresidioAnalyzerConfig.class)
                            .doesNotHaveBean("presidioHealthIndicator");
                });
    }

    @Test
    void nonPositiveResponseByteLimitFailsStartup() {
        this.contextRunner
                .withPropertyValues(
                        "spring.ai.privacy.presidio.enabled=true",
                        "spring.ai.privacy.presidio.max-response-bytes=0"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("maxResponseBytes must be positive");
                });
    }

    @Test
    void healthIndicatorIsOptionalWhenBootHealthIsAbsent() {
        this.contextRunner
                .withClassLoader(new FilteredClassLoader(HealthIndicator.class))
                .withPropertyValues("spring.ai.privacy.presidio.enabled=true")
                .run(context -> assertThat(context)
                        .hasSingleBean(PresidioAnalyzer.class)
                        .doesNotHaveBean("presidioHealthIndicator"));
    }

    @Test
    void healthIndicatorCallsHealthEndpointOnlyWhenInvoked() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        startHealthServer(exchange -> {
            requests.incrementAndGet();
            apiKey.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            method.set(exchange.getRequestMethod());
            byte[] response = "synthetic-secret-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        this.contextRunner
                .withPropertyValues(
                        "spring.ai.privacy.presidio.enabled=true",
                        "spring.ai.privacy.presidio.analyzer-url=" + baseUrl(),
                        "spring.ai.privacy.presidio.headers.X-API-Key=synthetic-secret-key"
                )
                .run(context -> {
                    assertThat(requests).hasValue(0);
                    Health health = context.getBean("presidioHealthIndicator", HealthIndicator.class).health();

                    assertThat(requests).hasValue(1);
                    assertThat(apiKey).hasValue("synthetic-secret-key");
                    assertThat(method).hasValue("GET");
                    assertThat(health.getStatus()).isEqualTo(Status.UP);
                    assertThat(health.getDetails()).containsExactlyEntriesOf(Map.of("status", 200));
                    assertThat(health.toString()).doesNotContain("synthetic-secret", baseUrl());
                });
    }

    @Test
    void healthIndicatorReportsOnlySafeFailureCategoryAndStatus() throws IOException {
        startHealthServer(exchange -> {
            byte[] response = "synthetic-secret-body".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });

        this.contextRunner
                .withPropertyValues(
                        "spring.ai.privacy.presidio.enabled=true",
                        "spring.ai.privacy.presidio.analyzer-url=" + baseUrl()
                )
                .run(context -> {
                    Health health = context.getBean("presidioHealthIndicator", HealthIndicator.class).health();

                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails())
                            .containsExactlyInAnyOrderEntriesOf(Map.of(
                                    "category", "ANALYZER_AUTHENTICATION_FAILED",
                                    "status", 401
                            ));
                    assertThat(health.toString()).doesNotContain("synthetic-secret", baseUrl());
                });
    }

    @Test
    void healthIndicatorOmitsStatusWhenNoHttpResponseIsReceived() throws IOException {
        startHealthServer(exchange -> exchange.close());
        String analyzerUrl = baseUrl();
        this.server.stop(0);
        this.server = null;

        this.contextRunner
                .withPropertyValues(
                        "spring.ai.privacy.presidio.enabled=true",
                        "spring.ai.privacy.presidio.analyzer-url=" + analyzerUrl
                )
                .run(context -> {
                    Health health = context.getBean("presidioHealthIndicator", HealthIndicator.class).health();

                    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                    assertThat(health.getDetails())
                            .containsExactlyEntriesOf(Map.of(
                                    "category", "ANALYZER_UNAVAILABLE"
                            ));
                });
    }

    @Test
    void healthIndicatorTimeoutBoundsStalledResponseBody() throws IOException {
        CountDownLatch partialBodyFlushed = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        startHealthServer(exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try {
                exchange.getResponseBody().write(body, 0, 1);
                exchange.getResponseBody().flush();
                partialBodyFlushed.countDown();
                releaseBody.await(2, TimeUnit.SECONDS);
                exchange.getResponseBody().write(body, 1, 1);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The health check requests cancellation of the pending client-side HTTP attempt.
            } finally {
                exchange.close();
            }
        });

        try {
            this.contextRunner
                    .withPropertyValues(
                            "spring.ai.privacy.presidio.enabled=true",
                            "spring.ai.privacy.presidio.analyzer-url=" + baseUrl(),
                            "spring.ai.privacy.presidio.timeout=500ms"
                    )
                    .run(context -> {
                        Health health = context.getBean("presidioHealthIndicator", HealthIndicator.class).health();

                        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                        assertThat(health.getDetails()).containsExactlyEntriesOf(Map.of(
                                "category", "ANALYZER_TIMEOUT"
                        ));
                        assertThat(partialBodyFlushed.getCount()).isZero();
                    });
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void malformedAnalyzerUriFailsDuringPropertyBinding() {
        this.contextRunner
                .withPropertyValues(
                        "spring.ai.privacy.presidio.enabled=true",
                        "spring.ai.privacy.presidio.analyzer-url=https://[synthetic-secret"
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("Invalid URI syntax"));
    }

    private void startHealthServer(HttpHandler handler) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/health", handler);
        this.server.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + this.server.getAddress().getPort();
    }
}
