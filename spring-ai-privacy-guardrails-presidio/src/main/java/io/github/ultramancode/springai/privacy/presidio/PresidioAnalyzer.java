package io.github.ultramancode.springai.privacy.presidio;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzer;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureSanitizer;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import io.github.ultramancode.springai.privacy.core.PrivacyGuardrailException;
import io.github.ultramancode.springai.privacy.core.PrivacyPhase;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Remote PII analyzer backed by the Presidio Analyzer HTTP API. */
public final class PresidioAnalyzer implements PiiAnalyzer {

    /** Stable provider ID used by core resolution and diagnostics. */
    public static final String PROVIDER_ID = "PRESIDIO";

    private final PresidioAnalyzerConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final PresidioResponseParser responseParser;

    /**
     * Creates an analyzer with default HTTP settings.
     *
     * @param analyzerUrl Presidio Analyzer base URL
     */
    public PresidioAnalyzer(String analyzerUrl) {
        this(PresidioAnalyzerConfig.defaults(analyzerUrl));
    }

    /**
     * Creates an analyzer with a newly constructed JDK HTTP client.
     *
     * @param config validated Presidio HTTP settings
     */
    public PresidioAnalyzer(PresidioAnalyzerConfig config) {
        this(
                Objects.requireNonNull(config, "config must not be null"),
                HttpClient.newBuilder().connectTimeout(config.timeout()).build()
        );
    }

    /**
     * Creates an analyzer with an application-supplied JDK HTTP client.
     *
     * @param config validated Presidio HTTP settings
     * @param httpClient reusable client used for analyzer requests
     */
    public PresidioAnalyzer(PresidioAnalyzerConfig config, HttpClient httpClient) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.objectMapper = new ObjectMapper();
        this.responseParser = new PresidioResponseParser(config.maxResponseBytes());
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public List<PiiSpan> analyze(String text, PiiAnalysisOptions options) {
        Objects.requireNonNull(text, "text must not be null");
        Objects.requireNonNull(options, "options must not be null");
        if (text.isBlank()) {
            return List.of();
        }

        return analyzeWithRetry(text, options);
    }

    private List<PiiSpan> analyzeWithRetry(String text, PiiAnalysisOptions options) {
        for (int attemptIndex = 0; ; attemptIndex++) {
            try {
                return callPresidio(text, options);
            } catch (PresidioCallException failure) {
                if (!failure.retryable() || attemptIndex >= this.config.maxRetries()) {
                    throw failure.withAttemptCount(attemptIndex + 1);
                }
                sleepBeforeRetry(this.config.retryBackoff());
            } catch (Throwable failure) {
                PrivacyFailureSanitizer.rethrowIfFatal(failure);
                throw new PresidioCallException(
                        "Presidio analyzer execution failed",
                        false,
                        PrivacyFailureCode.ANALYZER_EXECUTION_FAILED,
                        attemptIndex + 1
                );
            }
        }
    }

    private List<PiiSpan> callPresidio(String text, PiiAnalysisOptions options) {
        String body;
        try {
            // Core entity names are canonical, not Presidio-native; the core resolver owns post-filtering.
            body = this.objectMapper.writeValueAsString(
                    new PresidioAnalyzeRequest(text, options.language())
            );
        } catch (JacksonException serializationFailure) {
            throw new PresidioCallException(
                    "Could not serialize Presidio request",
                    false,
                    PrivacyFailureCode.ANALYZER_EXECUTION_FAILED
            );
        }

        HttpRequest request;
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(this.config.analyzeEndpoint())
                    .timeout(this.config.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            this.config.headers().forEach(requestBuilder::header);
            request = requestBuilder.build();
        } catch (IllegalArgumentException exception) {
            throw new PresidioCallException(
                    "Could not create Presidio analyzer request",
                    false,
                    PrivacyFailureCode.ANALYZER_EXECUTION_FAILED
            );
        }

        HttpResponse<byte[]> response;
        long attemptStartedAt = System.nanoTime();
        CompletableFuture<HttpResponse<byte[]>> pendingRequest = this.httpClient.sendAsync(
                request,
                BoundedBodySubscriber.handler(this.config.maxResponseBytes())
        );
        try {
            response = pendingRequest.get(
                    remainingTimeoutNanos(attemptStartedAt, this.config.timeout()),
                    TimeUnit.NANOSECONDS
            );
        } catch (TimeoutException timeout) {
            cancel(pendingRequest);
            throw new PresidioCallException(
                    "Presidio analyzer call timed out",
                    true,
                    PrivacyFailureCode.ANALYZER_TIMEOUT
            );
        } catch (ExecutionException failure) {
            if (hasCause(failure, HttpTimeoutException.class)) {
                throw new PresidioCallException(
                        "Presidio analyzer call timed out",
                        true,
                        PrivacyFailureCode.ANALYZER_TIMEOUT
                );
            }
            if (BoundedBodySubscriber.isBodyLimitExceeded(failure)) {
                throw new PresidioCallException(
                        "Presidio analyzer response exceeded the safe size limit",
                        false,
                        PrivacyFailureCode.ANALYZER_RESPONSE_INVALID
                );
            }
            if (!hasCause(failure, IOException.class)) {
                throw new PresidioCallException(
                        "Presidio analyzer execution failed",
                        false,
                        PrivacyFailureCode.ANALYZER_EXECUTION_FAILED
                );
            }
            throw new PresidioCallException(
                    "Presidio analyzer call failed",
                    true,
                    PrivacyFailureCode.ANALYZER_UNAVAILABLE
            );
        } catch (InterruptedException interruption) {
            cancel(pendingRequest);
            Thread.currentThread().interrupt();
            throw new PresidioCallException(
                    "Presidio analyzer call interrupted",
                    false,
                    PrivacyFailureCode.ANALYSIS_INTERRUPTED
            );
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new PresidioCallException(
                    "Presidio analyzer returned HTTP " + status,
                    isRetryableHttpStatus(status),
                    failureCodeForHttpErrorStatus(status)
            );
        }

        return this.responseParser.parse(response.body(), text);
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
        try {
            pendingRequest.cancel(true);
        } catch (Throwable failure) {
            PrivacyFailureSanitizer.rethrowIfFatal(failure);
        }
    }

    /**
     * Returns the stable failure category shared by analysis and health diagnostics.
     *
     * @param status unsuccessful three-digit HTTP status
     * @return privacy-safe analyzer failure category
     */
    public static PrivacyFailureCode failureCodeForHttpErrorStatus(int status) {
        requireHttpStatus(status);
        if (status >= 200 && status < 300) {
            throw new IllegalArgumentException("status must represent an unsuccessful HTTP response");
        }
        if (status == 401 || status == 403) {
            return PrivacyFailureCode.ANALYZER_AUTHENTICATION_FAILED;
        }
        if (status == 408) {
            return PrivacyFailureCode.ANALYZER_TIMEOUT;
        }
        if (status == 429) {
            return PrivacyFailureCode.ANALYZER_RATE_LIMITED;
        }
        if (status >= 500) {
            return PrivacyFailureCode.ANALYZER_UNAVAILABLE;
        }
        return PrivacyFailureCode.ANALYZER_EXECUTION_FAILED;
    }

    /**
     * Returns whether an analyzer call with this HTTP status may be retried.
     *
     * @param status three-digit HTTP status
     * @return {@code true} for timeout, rate-limit, and server-error statuses
     */
    public static boolean isRetryableHttpStatus(int status) {
        requireHttpStatus(status);
        return status == 408 || status == 429 || status >= 500;
    }

    private static void requireHttpStatus(int status) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("status must be a three-digit HTTP status code");
        }
    }

    private void sleepBeforeRetry(Duration duration) {
        if (duration.isZero()) {
            return;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(TimeUnit.NANOSECONDS.convert(duration));
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new PrivacyGuardrailException(
                    PrivacyFailureCode.ANALYSIS_INTERRUPTED,
                    PrivacyPhase.ANALYSIS,
                    "Retry backoff interrupted"
            );
        }
    }

    private record PresidioAnalyzeRequest(String text, String language) {
    }

}
