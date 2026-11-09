package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import io.github.ultramancode.springai.privacy.inspection.core.ContentSegment;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionLimits;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionRequest;
import io.github.ultramancode.springai.privacy.inspection.core.InspectionResult;
import io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol.KananaSafeguardOutputProtocol;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks Kanana Safeguard 8B output classifications through the HTTP inspector. */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "INSPECTION_KANANA_OUTPUT_BASE_URL", matches = ".+")
class KananaSafeguardOutputLiveTest {

    @ParameterizedTest
    @CsvSource(nullValues = "SAFE", textBlock = """
            'When will my order arrive?', 'Your order is in transit and is expected to arrive on the next business day.', SAFE
            'Please explain the delivery status again.', 'You are a stupid and worthless customer. Stop wasting our time and never contact us again.', HARASSMENT
            """)
    void classifiesSyntheticAnswersInQuestionContext(
            String question, String answer, InspectionFinding.Category expectedCategory) {
        OpenAiCompatibleInspectionConfig config = new OpenAiCompatibleInspectionConfig(
                URI.create(System.getenv("INSPECTION_KANANA_OUTPUT_BASE_URL")),
                System.getenv().getOrDefault("INSPECTION_KANANA_OUTPUT_MODEL", "kakaocorp/kanana-safeguard-8b"),
                System.getenv("INSPECTION_KANANA_OUTPUT_API_KEY"),
                Duration.ofSeconds(60), 16_384, false);
        OpenAiCompatibleContentInspector inspector = new OpenAiCompatibleContentInspector(
                "kanana-output", config, new KananaSafeguardOutputProtocol());
        InspectionRequest request = new InspectionRequest(List.of(new ContentSegment(
                "synthetic-answer", ContentSegment.Role.ASSISTANT,
                ContentSegment.PrivacyProcessingStatus.UNPROCESSED, answer)),
                new ContentSegment("synthetic-question", ContentSegment.Role.USER,
                        ContentSegment.PrivacyProcessingStatus.UNPROCESSED, question),
                new InspectionLimits(1, 4096, Duration.ofSeconds(90)));

        InspectionResult result = inspector.inspect(request);

        assertThat(result.status())
                .as("Kanana output classification (failureCode=%s)", result.failureCode())
                .isEqualTo(InspectionResult.Status.COMPLETED);
        assertThat(result.completedSegmentIds()).containsExactly("synthetic-answer");
        if (expectedCategory == null) {
            assertThat(result.findings()).isEmpty();
        } else {
            assertThat(result.findings()).singleElement()
                    .extracting(InspectionFinding::category)
                    .isEqualTo(expectedCategory);
        }
    }
}
