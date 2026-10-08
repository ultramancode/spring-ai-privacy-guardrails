package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionResultTest {

    @Test
    void rejectsInvalidResultArguments() {
        assertThatThrownBy(() -> InspectionResult.completed(Set.of("s1"), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> InspectionResult.completed(Set.of("bad id"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InspectionResult(InspectionResult.Status.COMPLETED,
                Set.of("s1"), List.of(), InspectionFailureCode.TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
