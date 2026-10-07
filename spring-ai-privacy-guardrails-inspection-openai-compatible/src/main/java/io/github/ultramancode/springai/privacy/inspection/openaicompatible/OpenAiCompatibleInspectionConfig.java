package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Explicit endpoint and model contract. The endpoint is the full chat/completions URL.
 *
 * @param endpoint full HTTP(S) chat-completions URL, including any deployment-specific query parameters
 * @param model deployed guard model name
 * @param apiKey bearer token, or null or blank to omit the Authorization header
 * @param requestTimeout maximum time per HTTP request, also bounded by the shared inspection deadline
 * @param maxResponseBytes maximum response body size in bytes
 * @param requirePrivacyProcessedContent whether every segment must already be marked PROCESSED.
 *        False permits UNKNOWN and UNPROCESSED content to be sent. This setting does not run privacy processing
 */
public record OpenAiCompatibleInspectionConfig(
        URI endpoint,
        String model,
        String apiKey,
        Duration requestTimeout,
        int maxResponseBytes,
        boolean requirePrivacyProcessedContent) {

    public OpenAiCompatibleInspectionConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getFragment() != null
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))) {
            throw new IllegalArgumentException(
                    "Endpoint must be an absolute HTTP(S) URL without credentials or fragment");
        }
        if (model == null
                || model.isBlank()
                || model.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "model must be non-blank and contain no control characters");
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
        if (apiKey != null
                && apiKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "apiKey must contain no control characters");
        }
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleInspectionConfig[endpoint=<configured>, model=<configured>, apiKey=<redacted>, requirePrivacyProcessedContent="
                + requirePrivacyProcessedContent
                + "]";
    }
}
