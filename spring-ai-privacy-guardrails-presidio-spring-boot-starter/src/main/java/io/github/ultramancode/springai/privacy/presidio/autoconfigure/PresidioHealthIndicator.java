package io.github.ultramancode.springai.privacy.presidio.autoconfigure;

import io.github.ultramancode.springai.privacy.core.PrivacyFailureSanitizer;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.presidio.PresidioAnalyzer;
import io.github.ultramancode.springai.privacy.presidio.PresidioAnalyzerConfig;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Reports Presidio reachability without retaining response content or transport failures. */
final class PresidioHealthIndicator implements HealthIndicator {

    private final PresidioAnalyzerConfig config;
    private final HttpClient httpClient;

    PresidioHealthIndicator(PresidioAnalyzerConfig config) {
        this(
                Objects.requireNonNull(config, "config must not be null"),
                HttpClient.newBuilder().connectTimeout(config.timeout()).build()
        );
    }

    PresidioHealthIndicator(PresidioAnalyzerConfig config, HttpClient httpClient) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
    }

    @Override
    public Health health() {
        CompletableFuture<HttpResponse<Void>> pendingRequest = null;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(this.config.healthEndpoint())
                    .timeout(this.config.timeout())
                    .GET();
            this.config.headers().forEach(builder::header);
            long attemptStartedAt = System.nanoTime();
            pendingRequest = this.httpClient.sendAsync(
                    builder.build(),
                    HttpResponse.BodyHandlers.discarding()
            );
            HttpResponse<Void> response = pendingRequest.get(
                    remainingTimeoutNanos(attemptStartedAt, this.config.timeout()),
                    TimeUnit.NANOSECONDS
            );
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return Health.up().withDetail("status", status).build();
            }
            return down(PresidioAnalyzer.failureCodeForHttpErrorStatus(status), status);
        } catch (TimeoutException failure) {
            cancel(pendingRequest);
            return down(PrivacyFailureCode.ANALYZER_TIMEOUT);
        } catch (ExecutionException failure) {
            if (hasCause(failure, HttpTimeoutException.class)) {
                return down(PrivacyFailureCode.ANALYZER_TIMEOUT);
            }
            if (hasCause(failure, IOException.class)) {
                return down(PrivacyFailureCode.ANALYZER_UNAVAILABLE);
            }
            return down(PrivacyFailureCode.ANALYZER_EXECUTION_FAILED);
        } catch (InterruptedException failure) {
            cancel(pendingRequest);
            Thread.currentThread().interrupt();
            return down(PrivacyFailureCode.ANALYSIS_INTERRUPTED);
        } catch (RuntimeException failure) {
            return down(PrivacyFailureCode.ANALYZER_EXECUTION_FAILED);
        } catch (Error failure) {
            PrivacyFailureSanitizer.rethrowIfFatal(failure);
            return down(PrivacyFailureCode.ANALYZER_EXECUTION_FAILED);
        }
    }

    private static long remainingTimeoutNanos(long attemptStartedAt, Duration timeout) {
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        long elapsedNanos = Math.max(0L, System.nanoTime() - attemptStartedAt);
        return Math.max(0L, timeoutNanos - elapsedNanos);
    }

    private static boolean hasCause(Throwable failure, Class<? extends Throwable> failureType) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean matched = false;
        for (Throwable current = failure;
             current != null && visited.add(current);
             current = current.getCause()) {
            PrivacyFailureSanitizer.rethrowIfFatal(current);
            if (failureType.isInstance(current)) {
                matched = true;
            }
        }
        return matched;
    }

    private static void cancel(CompletableFuture<?> pendingRequest) {
        if (pendingRequest == null) {
            return;
        }
        try {
            pendingRequest.cancel(true);
        } catch (Throwable failure) {
            PrivacyFailureSanitizer.rethrowIfFatal(failure);
        }
    }

    private Health down(PrivacyFailureCode category) {
        return Health.down().withDetail("category", category.name()).build();
    }

    private Health down(PrivacyFailureCode category, int status) {
        return Health.down()
                .withDetail("category", category.name())
                .withDetail("status", status)
                .build();
    }
}
