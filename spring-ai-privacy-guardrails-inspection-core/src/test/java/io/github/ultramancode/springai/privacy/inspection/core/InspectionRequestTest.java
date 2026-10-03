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

    @ParameterizedTest
    @CsvSource({
            "MODEL_ERROR, false, false, MODEL_ERROR",
            "CONFIGURATION, false, false, CONFIGURATION",
            "MODEL_ERROR, true, false, TIMEOUT",
            "CONFIGURATION, true, false, CONFIGURATION",
            "INVALID_RESULT, true, false, INVALID_RESULT",
            "MODEL_ERROR, true, true, CANCELLED",
            "CONFIGURATION, true, true, CANCELLED"
    })
    void resolvesFailureWithoutDowngradingHardFailuresOrClearingInterruption(
            InspectionFailureCode reported, boolean expired, boolean interrupted, InspectionFailureCode expected) {
        Duration timeout = Duration.ofMinutes(1);
        if (expired) {
            timeout = Duration.ofNanos(1);
        }
        ContentSegment segment = new ContentSegment("s1", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.UNKNOWN, "text");
        InspectionRequest request = new InspectionRequest(List.of(segment), new InspectionLimits(1, 100, 1, timeout));

        try {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            assertThat(request.resolveFailure(reported)).isEqualTo(expected);
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "false, false,",
            "true, false, TIMEOUT",
            "false, true, CANCELLED",
            "true, true, CANCELLED"
    })
    void checksSuccessfulCompletionWithoutClearingInterruption(
            boolean expired, boolean interrupted, InspectionFailureCode expected) {
        Duration timeout = Duration.ofMinutes(1);
        if (expired) {
            timeout = Duration.ofNanos(1);
        }
        ContentSegment segment = new ContentSegment("s1", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.UNKNOWN, "text");
        InspectionRequest request = new InspectionRequest(List.of(segment), new InspectionLimits(1, 100, 1, timeout));

        try {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (expected == null) {
                assertThatNoException().isThrownBy(request::checkActive);
            } else {
                assertThatThrownBy(request::checkActive)
                        .isInstanceOfSatisfying(InspectionException.class,
                                ex -> assertThat(ex.failure()).isEqualTo(expected));
            }
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void resolvingFailureRequiresAReportedFailure() {
        ContentSegment segment = new ContentSegment("s1", ContentSegment.Role.USER,
                ContentSegment.PrivacyProcessingStatus.UNKNOWN, "text");
        InspectionRequest request = new InspectionRequest(List.of(segment),
                new InspectionLimits(1, 100, 1, Duration.ofMinutes(1)));

        assertThatThrownBy(() -> request.resolveFailure(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("failure");
    }
}
