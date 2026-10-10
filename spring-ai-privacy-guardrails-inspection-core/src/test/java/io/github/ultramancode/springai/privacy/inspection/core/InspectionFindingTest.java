package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionFindingTest {

    @Test
    void rejectsNaNScore() {
        assertThatThrownBy(() -> new InspectionFinding(
                "s1", InspectionFinding.Category.PROMPT_ATTACK, "attack", Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
