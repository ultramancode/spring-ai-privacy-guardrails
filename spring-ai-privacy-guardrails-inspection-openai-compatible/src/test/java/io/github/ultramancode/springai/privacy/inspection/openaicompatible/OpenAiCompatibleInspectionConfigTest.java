package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleInspectionConfigTest {

    @ParameterizedTest
    @ValueSource(strings = {"HTTP", "HtTp", "HTTPS", "HtTpS"})
    void baseUrlSchemesAreCaseInsensitive(String scheme) {
        URI baseUrl = URI.create(scheme + "://localhost/v1");

        assertThatCode(() -> new OpenAiCompatibleInspectionConfig(
                baseUrl, "test-model", null, Duration.ofSeconds(2), 4096, true))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1",
            "//localhost/v1",
            "http:/v1",
            "ftp://localhost/v1",
            "http://user:password@localhost/v1",
            "http://localhost/v1#fragment"
    })
    void rejectsUnsupportedBaseUrls(String url) {
        assertThatThrownBy(() -> new OpenAiCompatibleInspectionConfig(
                URI.create(url), "test-model", null, Duration.ofSeconds(2), 4096, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void httpBudgetsMustBePositiveAndTimeoutMustFitInNanoseconds() {
        URI baseUrl = URI.create("http://localhost/v1");
        List<Duration> invalidTimeouts = List.of(
                Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE));

        for (Duration timeout : invalidTimeouts) {
            assertThatThrownBy(() -> new OpenAiCompatibleInspectionConfig(
                    baseUrl, "test-model", null, timeout, 4096, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (int bytes : new int[] {0, -1}) {
            assertThatThrownBy(() -> new OpenAiCompatibleInspectionConfig(
                    baseUrl, "test-model", null, Duration.ofSeconds(2), bytes, true))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @CsvSource({
            "https://example.test/v1?token=private-route&route=guard, https://example.test/v1?<redacted>",
            "https://example.test/v1, https://example.test/v1"
    })
    void toStringRedactsApiKeyAndQuery(String url, String displayedUrl) {
        URI baseUrl = URI.create(url);
        OpenAiCompatibleInspectionConfig config = new OpenAiCompatibleInspectionConfig(
                baseUrl, "test-model", "test-key", Duration.ofSeconds(2), 4096, true);

        assertThat(config.toString())
                .contains("baseUrl=" + displayedUrl + ",")
                .doesNotContain("test-key", "token=", "private-route", "route=guard");
    }
}
