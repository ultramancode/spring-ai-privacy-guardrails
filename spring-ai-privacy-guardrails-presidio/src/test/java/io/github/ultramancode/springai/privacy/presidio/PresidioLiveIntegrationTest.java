package io.github.ultramancode.springai.privacy.presidio;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live")
@EnabledIfEnvironmentVariable(named = "PRESIDIO_LIVE_TEST", matches = "true")
class PresidioLiveIntegrationTest {

    @Test
    void analyzerCallsPinnedDockerImage() {
        PresidioAnalyzer analyzer = new PresidioAnalyzer("http://localhost:5002");

        assertThat(analyzer.analyze(
                "My name is John Smith and my email is john.smith@example.com.",
                PiiAnalysisOptions.builder().language("en").build()
        )).extracting(PiiSpan::entityType)
                .contains("PERSON", "EMAIL_ADDRESS");
    }
}
