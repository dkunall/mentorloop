package com.mentorloop.adaptive.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class BktModelTest {

    private final BktModel bkt = new BktModel();
    private final BktParams p = new BktParams(0.3, 0.1, 0.1, 0.2);

    @Test
    void followsAHandComputedSequence() {
        // Reference values computed independently (Python, same formulas) for answers right, right, wrong, right, right.
        double[] expected = {0.6926829268292684, 0.9192307692307693, 0.6285012285012287, 0.8955079474775399,
                0.9772528960433278};
        boolean[] answers = {true, true, false, true, true};
        double known = p.pInit();
        for (int i = 0; i < answers.length; i++) {
            known = bkt.update(known, answers[i], p);
            assertThat(known).as("after answer %d", i + 1).isCloseTo(expected[i], within(1e-12));
        }
    }

    @Test
    void predictsTheNextAnswerFromSlipAndGuess() {
        assertThat(bkt.predictCorrect(0.3, p)).isCloseTo(0.41, within(1e-12));
        assertThat(bkt.predictCorrect(1.0, p)).isCloseTo(0.9, within(1e-5));
        assertThat(bkt.predictCorrect(0.0, p)).isCloseTo(0.2, within(1e-5));
    }

    @Test
    void aRightAnswerNeverLowersAndAWrongOneOnlyLowersBeforeLearning() {
        for (double known = 0.01; known < 1; known += 0.07) {
            assertThat(bkt.update(known, true, p)).isGreaterThan(known);
            double afterWrong = bkt.update(known, false, p);
            assertThat(afterWrong).isBetween(0.0, 1.0);
        }
    }

    @Test
    void neverReachesExactlyZeroOrOne() {
        double known = 0.5;
        for (int i = 0; i < 200; i++) {
            known = bkt.update(known, true, p);
        }
        assertThat(known).isLessThan(1.0);
        assertThat(bkt.update(known, false, p)).isLessThan(known);
    }

    @Test
    void rejectsDegenerateParameters() {
        assertThatThrownBy(() -> new BktParams(0.2, 0.1, 0.6, 0.5)).hasMessageContaining("pSlip + pGuess");
        assertThatThrownBy(() -> new BktParams(0, 0.1, 0.1, 0.2)).hasMessageContaining("pInit");
    }
}
