package io.github.ultramancode.springai.privacy.inspection.openaicompatible.protocol;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuardModelGenerationOptionsTest {

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsNonpositiveCompletionTokenLimits(long maxCompletionTokens) {
        assertThatThrownBy(() -> new GuardModelGenerationOptions(maxCompletionTokens, 0.0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxCompletionTokens must be positive");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.1, 2.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidTemperatures(double temperature) {
        assertThatThrownBy(() -> new GuardModelGenerationOptions(32L, temperature))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("temperature must be between 0.0 and 2.0");
    }
}
