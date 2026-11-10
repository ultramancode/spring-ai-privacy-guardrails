package io.github.ultramancode.springai.privacy.inspection.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.OptionalDouble;

import static io.github.ultramancode.springai.privacy.inspection.core.InspectionFinding.Category.PROMPT_ATTACK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InspectionFindingTest {

    @Test
    void representsMissingScoreAsEmpty() {
        InspectionFinding finding = new InspectionFinding("s1", PROMPT_ATTACK, "attack");

        assertThat(finding.score()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 0.5, 1.0})
    void preservesPresentScoresIncludingZero(double score) {
        InspectionFinding finding = new InspectionFinding("s1", PROMPT_ATTACK, "attack", score);

        assertThat(finding.score()).hasValue(score);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1})
    void rejectsInvalidScoresThroughEitherConstructor(double score) {
        assertThatThrownBy(() -> new InspectionFinding("s1", PROMPT_ATTACK, "attack", score))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InspectionFinding("s1", PROMPT_ATTACK, "attack", OptionalDouble.of(score)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullScore() {
        assertThatThrownBy(() -> new InspectionFinding("s1", PROMPT_ATTACK, "attack", null))
                .isInstanceOf(NullPointerException.class);
    }
}
