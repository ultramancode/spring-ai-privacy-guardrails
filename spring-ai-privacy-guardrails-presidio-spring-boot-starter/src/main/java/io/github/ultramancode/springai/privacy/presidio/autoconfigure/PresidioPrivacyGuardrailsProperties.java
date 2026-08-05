package io.github.ultramancode.springai.privacy.presidio.autoconfigure;

import io.github.ultramancode.springai.privacy.presidio.PresidioAnalyzerConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Configuration for the Presidio analyzer provider. */
@ConfigurationProperties("spring.ai.privacy.presidio")
public class PresidioPrivacyGuardrailsProperties {

    /** Whether the Presidio analyzer is enabled. */
    private boolean enabled = false;
    /** Base URI of the Presidio analyzer service. */
    private URI analyzerUrl = URI.create("http://localhost:5002");
    /** Timeout for each complete analyzer HTTP attempt and health check. */
    private Duration timeout = PresidioAnalyzerConfig.DEFAULT_TIMEOUT;
    /** Number of retries after the initial request. */
    private int maxRetries = PresidioAnalyzerConfig.DEFAULT_MAX_RETRIES;
    /** Delay between retry attempts. */
    private Duration retryBackoff = PresidioAnalyzerConfig.DEFAULT_RETRY_BACKOFF;
    /** Maximum Presidio response-body size in bytes. */
    private int maxResponseBytes = PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES;
    /** Additional HTTP headers sent to the Presidio service. */
    private Map<String, String> headers = new LinkedHashMap<>();

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public URI getAnalyzerUrl() {
        return this.analyzerUrl;
    }

    public void setAnalyzerUrl(URI analyzerUrl) {
        this.analyzerUrl = analyzerUrl;
    }

    public Duration getTimeout() {
        return this.timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getMaxRetries() {
        return this.maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getRetryBackoff() {
        return this.retryBackoff;
    }

    public void setRetryBackoff(Duration retryBackoff) {
        this.retryBackoff = retryBackoff;
    }

    public int getMaxResponseBytes() {
        return this.maxResponseBytes;
    }

    public void setMaxResponseBytes(int maxResponseBytes) {
        this.maxResponseBytes = maxResponseBytes;
    }

    public Map<String, String> getHeaders() {
        return this.headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }
}
