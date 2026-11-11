package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionRequestTest {

    @Test
    void userMessageSharesCharacterBudgetWithoutBecomingATarget() {
        ContentSegment answer = new ContentSegment("answer", ContentSegment.Role.ASSISTANT,
                ContentSegment.PrivacyProcessingStatus.PROCESSED, "answer");
        ContentSegment question = new ContentSegment("question", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.PROCESSED, "question");
        InspectionLimits limits = new InspectionLimits(1, 14, Duration.ofSeconds(1));
        InspectionRequest request = new InspectionRequest(List.of(answer), question, limits);

        assertThat(request.segments()).containsExactly(answer);
        assertThat(request.userMessage()).contains(question);
        assertThat(request.toString()).doesNotContain("question", "answer");
        assertThatThrownBy(() -> new InspectionRequest(List.of(answer), question,
                new InspectionLimits(1, 13, Duration.ofSeconds(1))))
                .hasMessageContaining("LIMIT_EXCEEDED");
        assertThatThrownBy(() -> new InspectionRequest(List.of(answer), answer, limits))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void privacyRequirementIncludesUserMessage() {
        ContentSegment answer = new ContentSegment("answer", ContentSegment.Role.ASSISTANT,
                ContentSegment.PrivacyProcessingStatus.PROCESSED, "safe answer");
        ContentSegment question = new ContentSegment("question", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.UNKNOWN, "private question");
        InspectionRequest request = new InspectionRequest(List.of(answer), question, InspectionLimits.defaults());

        assertThatThrownBy(request::requirePrivacyProcessed).hasMessageContaining("PRIVACY_PROCESSING_REQUIRED");
    }

    @Test
    void tokenSimplificationIncludesUserMessage() {
        ContentSegment answer = new ContentSegment("answer", ContentSegment.Role.ASSISTANT,
                ContentSegment.PrivacyProcessingStatus.PROCESSED, "Hello [PII:opaque]");
        ContentSegment question = new ContentSegment("question", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.PROCESSED, "I am [PII:opaque]");
        InspectionRequest request = new InspectionRequest(List.of(answer), question, InspectionLimits.defaults());

        InspectionRequest simplified = request.withSimplifiedPiiTokens(text -> text.replace("[PII:opaque]", "[PERSON]"));

        assertThat(simplified.segments().get(0).text()).isEqualTo("Hello [PERSON]");
        assertThat(simplified.userMessage().orElseThrow().text()).isEqualTo("I am [PERSON]");
        assertThat(simplified.userMessage().orElseThrow().privacyProcessingStatus())
                .isEqualTo(ContentSegment.PrivacyProcessingStatus.PROCESSED);
        assertThat(request.userMessage()).contains(question);
    }

    @Test
    void validatesLimitsAndDoesNotExposeRequestText() {
        ContentSegment segment = new ContentSegment("s1", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.UNPROCESSED, "private raw text");
        List<ContentSegment> segments = List.of(segment);
        InspectionRequest request = new InspectionRequest(segments, InspectionLimits.defaults());
        InspectionLimits restrictiveLimits = new InspectionLimits(1, 1, Duration.ofSeconds(1));

        assertThat(request.toString()).doesNotContain("private raw text");
        assertThat(segment.toString()).doesNotContain("private raw text");
        assertThatThrownBy(() -> new InspectionLimits(1, 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InspectionRequest(segments, restrictiveLimits))
                .hasMessageContaining("LIMIT_EXCEEDED");
        assertThatThrownBy(() -> new InspectionRequest(List.of(segment, segment), InspectionLimits.defaults()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "false, false,",
            "true, false, TIMEOUT",
            "false, true, CANCELLED",
            "true, true, CANCELLED"
    })
    void checksExecutionBudgetWithoutClearingInterruption(
            boolean expired, boolean interrupted, InspectionFailureCode expectedFailureCode) {
        Duration timeout = Duration.ofMinutes(1);
        if (expired) {
            timeout = Duration.ofNanos(1);
        }
        ContentSegment segment = new ContentSegment("s1", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.UNKNOWN, "text");
        InspectionRequest request = new InspectionRequest(List.of(segment), new InspectionLimits(1, 100, timeout));

        try {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (expectedFailureCode == null) {
                assertThatNoException().isThrownBy(request::checkActive);
            } else {
                assertThatThrownBy(request::checkActive)
                        .isInstanceOfSatisfying(InspectionException.class,
                                ex -> assertThat(ex.code()).isEqualTo(expectedFailureCode));
            }
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
        } finally {
            Thread.interrupted();
        }
    }
}
