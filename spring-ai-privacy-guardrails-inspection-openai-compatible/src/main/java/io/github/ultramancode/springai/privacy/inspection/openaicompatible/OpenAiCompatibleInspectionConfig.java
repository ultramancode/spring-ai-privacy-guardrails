package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Explicit endpoint and model contract; the endpoint is the full chat/completions URL. */
public record OpenAiCompatibleInspectionConfig(
        URI endpoint,
        String model,
        String apiKey,
        Duration requestTimeout,
        int maxResponseBytes,
        boolean allowRawContent) {

    public OpenAiCompatibleInspectionConfig {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (!endpoint.isAbsolute()
                || endpoint.getHost() == null
                || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null
                || endpoint.getFragment() != null
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))) {
            throw new IllegalArgumentException(
                    "Endpoint must be an absolute HTTP(S) URL without credentials, query or fragment");
        }
        if (model == null
                || model.isBlank()
                || model.length() > 256
                || model.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "model must be non-blank, at most 256 characters and contain no control characters");
        }
        if (requestTimeout.isZero()
                || requestTimeout.isNegative()
                || requestTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("requestTimeout must be positive and at most 10 minutes");
        }
        if (maxResponseBytes < 1 || maxResponseBytes > 1_048_576) {
            throw new IllegalArgumentException("maxResponseBytes must be between 1 and 1048576");
        }
        if (apiKey != null
                && (apiKey.length() > 4096 || apiKey.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException(
                    "apiKey must be at most 4096 characters and contain no control characters");
        }
    }

    public static OpenAiCompatibleInspectionConfig kanana(URI endpoint) {
        return new OpenAiCompatibleInspectionConfig(
                endpoint,
                "kakaocorp/kanana-safeguard-prompt-2.1b",
                null,
                Duration.ofSeconds(10),
                16_384,
                false);
    }

    @Override
    public String toString() {
        return "OpenAiCompatibleInspectionConfig[endpoint=<configured>, model=<configured>, apiKey=<redacted>, allowRawContent="
                + allowRawContent
                + "]";
    }
}
