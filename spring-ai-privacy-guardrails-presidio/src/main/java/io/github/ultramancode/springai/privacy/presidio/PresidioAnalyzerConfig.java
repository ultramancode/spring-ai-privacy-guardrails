package io.github.ultramancode.springai.privacy.presidio;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable HTTP settings for a Presidio analyzer provider.
 *
 * @param analyzerUrl Presidio Analyzer base URI without query, fragment, or user information
 * @param timeout positive connect and full HTTP-attempt timeout
 * @param maxRetries number of retries after the initial attempt
 * @param retryBackoff non-negative delay between retry attempts
 * @param maxResponseBytes positive maximum response-body size in bytes
 * @param headers additional HTTP request headers; {@code Content-Type} is managed by the adapter
 */
public record PresidioAnalyzerConfig(
        URI analyzerUrl,
        Duration timeout,
        int maxRetries,
        Duration retryBackoff,
        int maxResponseBytes,
        Map<String, String> headers
) {

    // HTTP field names use the RFC 9110 token character set.
    private static final Pattern HEADER_NAME_PATTERN = Pattern.compile(
            "[!#$%&'*+.^_`|~0-9A-Za-z-]+"
    );

    /** Default connect and full HTTP-attempt timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    /** Default number of retries after the initial request. */
    public static final int DEFAULT_MAX_RETRIES = 1;

    /** Default delay between retry attempts. */
    public static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(300);

    /** Default maximum response-body size in bytes. */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /** Validates and defensively copies Presidio HTTP settings. */
    public PresidioAnalyzerConfig {
        if (analyzerUrl == null) {
            throw new IllegalArgumentException("analyzerUrl must not be null");
        }
        if (timeout == null) {
            throw new IllegalArgumentException("timeout must not be null");
        }
        if (retryBackoff == null) {
            throw new IllegalArgumentException("retryBackoff must not be null");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        if (retryBackoff.isNegative()) {
            throw new IllegalArgumentException("retryBackoff must be >= 0");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }
        if (!"http".equalsIgnoreCase(analyzerUrl.getScheme())
                && !"https".equalsIgnoreCase(analyzerUrl.getScheme())) {
            throw new IllegalArgumentException("analyzerUrl must use HTTP or HTTPS");
        }
        if (analyzerUrl.getHost() == null || analyzerUrl.getHost().isBlank()) {
            throw new IllegalArgumentException("analyzerUrl must include a host");
        }
        if (analyzerUrl.getUserInfo() != null || analyzerUrl.getQuery() != null || analyzerUrl.getFragment() != null) {
            throw new IllegalArgumentException("analyzerUrl must not include user info, query, or fragment");
        }
        Map<String, String> validatedHeaders = new LinkedHashMap<>();
        Set<String> caseInsensitiveHeaderNames = new HashSet<>();
        Objects.requireNonNull(headers, "headers must not be null").forEach((name, value) -> {
            if (name == null || name.isBlank() || value == null || value.isBlank()) {
                throw new IllegalArgumentException("header names and values must not be null or blank");
            }
            if (!HEADER_NAME_PATTERN.matcher(name).matches() || !isValidHeaderValue(value)) {
                throw new IllegalArgumentException("header names and values must use valid HTTP syntax");
            }
            if ("content-type".equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("Content-Type is managed by the Presidio adapter");
            }
            if (!caseInsensitiveHeaderNames.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("header names must be unique ignoring case");
            }
            validatedHeaders.put(name, value);
        });
        headers = Map.copyOf(validatedHeaders);
    }

    private static boolean isValidHeaderValue(String value) {
        // Reject controls, especially CR/LF, while accepting visible and extended bytes.
        return value.chars().allMatch(character ->
                (character >= 0x20 && character <= 0x7e)
                        || (character >= 0x80 && character <= 0xff)
        );
    }

    /**
     * Creates settings for a base URL using the adapter defaults and no extra headers.
     *
     * @param analyzerUrl Presidio Analyzer base URL
     * @return validated default settings
     */
    public static PresidioAnalyzerConfig defaults(String analyzerUrl) {
        return new PresidioAnalyzerConfig(
                URI.create(analyzerUrl),
                DEFAULT_TIMEOUT,
                DEFAULT_MAX_RETRIES,
                DEFAULT_RETRY_BACKOFF,
                DEFAULT_MAX_RESPONSE_BYTES,
                Map.of()
        );
    }

    /**
     * Returns the analyzer endpoint derived from the validated base URI.
     *
     * @return base URI with the {@code analyze} path appended
     */
    public URI analyzeEndpoint() {
        return endpoint("analyze");
    }

    /**
     * Returns the health endpoint derived from the validated base URI.
     *
     * @return base URI with the {@code health} path appended
     */
    public URI healthEndpoint() {
        return endpoint("health");
    }

    private URI endpoint(String path) {
        String base = this.analyzerUrl.toString();
        String baseWithoutTrailingSlash = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(baseWithoutTrailingSlash + "/" + path);
    }

    @Override
    public String toString() {
        return "PresidioAnalyzerConfig[scheme=" + this.analyzerUrl.getScheme()
                + ", timeout=" + this.timeout
                + ", maxRetries=" + this.maxRetries
                + ", retryBackoff=" + this.retryBackoff
                + ", maxResponseBytes=" + this.maxResponseBytes
                + ", headerCount=" + this.headers.size() + "]";
    }
}
