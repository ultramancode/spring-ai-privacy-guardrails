package io.github.ultramancode.springai.privacy.sample.mcp.service;

import io.github.ultramancode.springai.privacy.sample.PrivacyGuardrailsDemoApplication;
import io.github.ultramancode.springai.privacy.sample.mcp.infrastructure.McpDemoClient;
import io.github.ultramancode.springai.privacy.springai.ToolDisclosurePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.ContextClosedEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class McpToolLoopDemoServiceIntegrationTest {

    @Test
    void completesRunningRequestBeforeShutdown() throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                PrivacyGuardrailsDemoApplication.class, TestConfig.class)
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--spring.lifecycle.timeout-per-shutdown-phase=3s");
        TestConfig testConfig = context.getBean(TestConfig.class);
        McpDemoClient client = context.getBean(McpDemoClient.class);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/demo/mcp-tool-loop"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            Future<HttpResponse<String>> response = executor.submit(
                    () -> httpClient.send(request, HttpResponse.BodyHandlers.ofString()));
            assertThat(testConfig.requestPaused.await(10, TimeUnit.SECONDS)).isTrue();

            CountDownLatch shutdownStarted = new CountDownLatch(1);
            CountDownLatch shutdownListenersCompleted = new CountDownLatch(1);
            context.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent) {
                    shutdownListenersCompleted.countDown();
                }
            });
            Future<?> shutdown = executor.submit(() -> {
                shutdownStarted.countDown();
                context.close();
            });
            assertThat(shutdownStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(shutdownListenersCompleted.await(200, TimeUnit.MILLISECONDS)).isFalse();

            testConfig.resumeRequest.countDown();
            HttpResponse<String> result = response.get(5, TimeUnit.SECONDS);
            assertThat(result.statusCode()).isEqualTo(200);
            shutdown.get(5, TimeUnit.SECONDS);
            assertThat(shutdownListenersCompleted.getCount()).isZero();
            assertThatThrownBy(client::connect).hasMessage("MCP demo client is closed");
        }
        finally {
            testConfig.resumeRequest.countDown();
            context.close();
            executor.shutdownNow();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {

        private final CountDownLatch requestPaused = new CountDownLatch(1);
        private final CountDownLatch resumeRequest = new CountDownLatch(1);

        @Bean
        @Primary
        ToolDisclosurePolicy testToolDisclosurePolicy() {
            ToolDisclosurePolicy delegate = ToolDisclosurePolicy.allowEntityTypesByToolName(
                    Map.of("customerLookup", List.of("CUSTOMER_ID")));
            return toolDefinition -> {
                this.requestPaused.countDown();
                try {
                    if (!this.resumeRequest.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to resume the demo request");
                    }
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to resume the demo request", failure);
                }
                return delegate.getAllowedEntityTypes(toolDefinition);
            };
        }
    }
}
