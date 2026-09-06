package io.github.ultramancode.springai.privacy.presidio;

import io.github.ultramancode.springai.privacy.core.PiiAnalysisOptions;
import io.github.ultramancode.springai.privacy.core.PiiSpan;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;

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

    @Test
    void analyzerCallsPinnedDockerBatchApiWithIndependentResults() {
        PresidioAnalyzer analyzer = new PresidioAnalyzer("http://localhost:5002");
        List<String> texts = List.of(
                "My name is John Smith.",
                "Contact john.smith@example.com."
        );

        List<List<PiiSpan>> results = analyzer.analyzeSegments(
                texts,
                PiiAnalysisOptions.builder().language("en").build()
        );

        assertThat(results).hasSize(2);
        assertThat(results.get(0))
                .filteredOn(span -> "PERSON".equals(span.entityType()))
                .anySatisfy(span -> assertThat(
                        texts.get(0).substring(span.start(), span.end())
                ).isEqualTo("John Smith"));
        assertThat(results.get(1))
                .filteredOn(span -> "EMAIL_ADDRESS".equals(span.entityType()))
                .anySatisfy(span -> assertThat(
                        texts.get(1).substring(span.start(), span.end())
                ).isEqualTo("john.smith@example.com"));
    }
}
