package io.github.ultramancode.springai.privacy.presidio;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiAnalyzerFailureMetadata;
import io.github.ultramancode.springai.privacy.core.PrivacyFailureCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(30)
class PresidioAnalyzerTimeoutTest {

    @Test
    void cancelsStalledHttpAttemptsAndReportsAttemptCount() {
        HttpClient httpClient = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> firstAttempt = new CompletableFuture<>();
        CompletableFuture<HttpResponse<byte[]>> retryAttempt = new CompletableFuture<>();
        // Neither attempt completes. The analyzer must enforce its deadline and cancel both.
        when(httpClient.<byte[]>sendAsync(any(HttpRequest.class), any()))
                .thenReturn(firstAttempt)
                .thenReturn(retryAttempt);
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config(), httpClient);

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .hasMessage("Presidio analyzer call timed out")
                .hasNoCause()
                .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_TIMEOUT);
                    assertThat(failure.attemptCount()).isEqualTo(2);
                });
        verify(httpClient, times(2)).sendAsync(any(HttpRequest.class), any());
        assertThat(firstAttempt).isCancelled();
        assertThat(retryAttempt).isCancelled();
    }

    @Test
    void retriesHttpClientTimeoutsAndReportsAttemptCount() {
        HttpClient httpClient = mock(HttpClient.class);
        when(httpClient.<byte[]>sendAsync(any(HttpRequest.class), any()))
                .thenAnswer(invocation -> CompletableFuture.failedFuture(
                        new HttpTimeoutException("HTTP client timeout")
                ));
        PresidioAnalyzer analyzer = new PresidioAnalyzer(config(), httpClient);

        assertThatThrownBy(() -> analyzer.analyze("Alice", PiiAnalysisOptions.defaults()))
                .hasMessage("Presidio analyzer call timed out")
                .hasNoCause()
                .isInstanceOfSatisfying(PiiAnalyzerFailureMetadata.class, failure -> {
                    assertThat(failure.code()).isEqualTo(PrivacyFailureCode.ANALYZER_TIMEOUT);
                    assertThat(failure.attemptCount()).isEqualTo(2);
                });
        verify(httpClient, times(2)).sendAsync(any(HttpRequest.class), any());
    }

    private static PresidioAnalyzerConfig config() {
        return new PresidioAnalyzerConfig(
                URI.create("http://presidio.invalid"),
                Duration.ofMillis(50),
                1,
                Duration.ZERO,
                PresidioAnalyzerConfig.DEFAULT_MAX_RESPONSE_BYTES,
                Map.of()
        );
    }
}
