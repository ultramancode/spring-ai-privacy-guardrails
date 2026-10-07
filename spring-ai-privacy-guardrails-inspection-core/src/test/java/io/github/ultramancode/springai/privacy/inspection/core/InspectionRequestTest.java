package io.github.ultramancode.springai.privacy.inspection.core;

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
            "false, false,",
            "true, false, TIMEOUT",
            "false, true, CANCELLED",
            "true, true, CANCELLED"
    })
    void checksExecutionBudgetWithoutClearingInterruption(
            boolean expired, boolean interrupted, InspectionFailureCode expected) {
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
            if (expected == null) {
                assertThatNoException().isThrownBy(request::checkActive);
            } else {
                assertThatThrownBy(request::checkActive)
                        .isInstanceOfSatisfying(InspectionException.class,
                                ex -> assertThat(ex.failureCode()).isEqualTo(expected));
            }
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
        } finally {
            Thread.interrupted();
        }
    }
}
