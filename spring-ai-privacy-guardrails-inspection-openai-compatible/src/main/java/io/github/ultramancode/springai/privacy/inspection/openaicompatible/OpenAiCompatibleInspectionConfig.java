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
 * @param requestTimeout maximum time per HTTP request, also limited by the time remaining for inspection
 * @param maxResponseBytes maximum response body size in bytes
 * @param requirePrivacyProcessedContent whether all segments must be marked {@code PROCESSED} before sending.
 *        This setting does not perform privacy processing.
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
}
