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
                                ex -> assertThat(ex.failureCode()).isEqualTo(expectedFailureCode));
            }
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
        } finally {
            Thread.interrupted();
        }
    }
}
