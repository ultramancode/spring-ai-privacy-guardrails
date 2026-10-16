package io.github.ultramancode.springai.privacy.inspection.openaicompatible;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextWindowConfigTest {

    @ParameterizedTest
    @CsvSource({"0,0,1", "-1,0,1", "4,-1,1", "4,4,1", "4,5,1", "4,0,0", "4,0,-1"})
    void rejectsInvalidWindowLimits(int characters, int overlap, int windows) {
        assertThatThrownBy(() -> new TextWindowConfig(characters, overlap, windows))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
