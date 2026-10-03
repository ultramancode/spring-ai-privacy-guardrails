package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionRequestTest {

    @ParameterizedTest
    @CsvSource({
            ", false, false,",
            "MODEL_ERROR, false, false, MODEL_ERROR",
            "CONFIGURATION, false, false, CONFIGURATION",
            ", true, false, TIMEOUT",
            "MODEL_ERROR, true, false, TIMEOUT",
            "CONFIGURATION, true, false, CONFIGURATION",
            "INVALID_RESULT, true, false, INVALID_RESULT",
            ", true, true, CANCELLED",
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
}
