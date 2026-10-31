package com.mentorloop.assessment.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RaschCalibratorTest {

    @Test
    void recoversTheOrderingOfSimulatedItemDifficulties() {
        Random rnd = new Random(7);
        double[] truth = {-2, -1, 0, 1, 2};
        List<UUID> items = new ArrayList<>();
        for (int i = 0; i < truth.length; i++) {
            items.add(new UUID(9, i));
        }
        List<RaschCalibrator.Response> responses = new ArrayList<>();
        for (int l = 0; l < 300; l++) {
            double theta = rnd.nextGaussian();
            for (int i = 0; i < truth.length; i++) {
                boolean right = rnd.nextDouble() < RaschCalibrator.sigmoid(theta - truth[i]);
                responses.add(new RaschCalibrator.Response("l" + l, items.get(i), right));
            }
        }
        var fit = new RaschCalibrator().fit(responses, 100);
        for (int i = 1; i < truth.length; i++) {
            assertThat(fit.difficulty().get(items.get(i))).isGreaterThan(fit.difficulty().get(items.get(i - 1)));
        }
        double mean = fit.difficulty().values().stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        assertThat(mean).isBetween(-1e-9, 1e-9);
        assertThat(fit.responses().get(items.get(0))).isEqualTo(300);
    }

    @Test
    void staysFiniteWhenEveryoneGetsAnItemRight() {
        UUID easy = new UUID(9, 1);
        UUID hard = new UUID(9, 2);
        List<RaschCalibrator.Response> r = new ArrayList<>();
        for (int l = 0; l < 20; l++) {
            r.add(new RaschCalibrator.Response("l" + l, easy, true));
            r.add(new RaschCalibrator.Response("l" + l, hard, l % 4 == 0));
        }
        var fit = new RaschCalibrator().fit(r, 200);
        assertThat(fit.difficulty().get(easy)).isFinite().isLessThan(fit.difficulty().get(hard));
    }
}
