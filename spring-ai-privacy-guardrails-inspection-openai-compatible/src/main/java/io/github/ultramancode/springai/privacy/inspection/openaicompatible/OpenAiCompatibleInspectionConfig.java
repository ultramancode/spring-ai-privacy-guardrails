package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Connection settings and privacy requirements for an HTTP guard model.
 *
 * @param baseUrl HTTP(S) API base URL, such as {@code http://localhost:8000/v1}
 * @param model deployed guard model name
 * @param apiKey bearer token. A null or blank value sends {@code Bearer not-required} for unauthenticated servers
 * @param requestTimeout time limit for a complete HTTP request, including reading the response body.
 *        The remaining inspection time may impose a shorter limit.
 * @param maxResponseBytes maximum response body size in bytes
 * @param requirePrivacyProcessedContent whether all segments must be marked {@code PROCESSED} before sending.
 */
public record OpenAiCompatibleInspectionConfig(
        URI baseUrl,
        String model,
        String apiKey,
        Duration requestTimeout,
        int maxResponseBytes,
        boolean requirePrivacyProcessedContent) {

    public OpenAiCompatibleInspectionConfig {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        String scheme = baseUrl.getScheme();
        boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!httpScheme || baseUrl.getHost() == null) {
            throw new IllegalArgumentException("baseUrl must be an HTTP(S) URL with a host");
        }
        if (baseUrl.getUserInfo() != null || baseUrl.getFragment() != null) {
            throw new IllegalArgumentException("baseUrl must not contain credentials or a fragment");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        try {
            requestTimeout.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("requestTimeout must fit in nanoseconds");
        }
        if (maxResponseBytes < 1) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
    }

    @Override
    public String toString() {
        String displayedBaseUrl = baseUrl.toString();
        int queryStart = displayedBaseUrl.indexOf('?');
        if (queryStart >= 0) {
            displayedBaseUrl = displayedBaseUrl.substring(0, queryStart) + "?<redacted>";
        }
        return "OpenAiCompatibleInspectionConfig[baseUrl="
                + displayedBaseUrl
                + ", model=<configured>, apiKey=<redacted>, requirePrivacyProcessedContent="
                + requirePrivacyProcessedContent
                + "]";
    }

    /**
     * Creates a builder with a one-minute timeout and a 2 MiB response limit.
     */
    public static Builder builder(URI baseUrl, String model) {
        return new Builder(baseUrl, model);
    }

    /** Configures the connection to an HTTP guard model. */
    public static final class Builder {
        private final URI baseUrl;
        private final String model;
        private String apiKey;
        private Duration requestTimeout = Duration.ofMinutes(1);
        private int maxResponseBytes = 2 * 1024 * 1024;
        private boolean requirePrivacyProcessedContent = false;

        private Builder(URI baseUrl, String model) {
            this.baseUrl = baseUrl;
            this.model = model;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder maxResponseBytes(int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
            return this;
        }

        /**
         * Requires every segment to be marked {@code PROCESSED} before sending when enabled.
         * Defaults to {@code false}.
         */
        public Builder requirePrivacyProcessedContent(boolean required) {
            this.requirePrivacyProcessedContent = required;
            return this;
        }

        public OpenAiCompatibleInspectionConfig build() {
            return new OpenAiCompatibleInspectionConfig(baseUrl, model, apiKey, requestTimeout,
                maxResponseBytes, requirePrivacyProcessedContent);
        }
    }
}
