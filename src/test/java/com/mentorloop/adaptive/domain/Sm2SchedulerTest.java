package com.mentorloop.adaptive.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class Sm2SchedulerTest {

    private final Sm2Scheduler sm2 = new Sm2Scheduler();

    private List<ReviewCard> run(int... qualities) {
        List<ReviewCard> out = new ArrayList<>();
        ReviewCard c = ReviewCard.fresh();
        for (int q : qualities) {
            c = sm2.next(c, q);
            out.add(c);
        }
        return out;
    }

    @Test
    void perfectRecallGivesTheTextbookIntervals() {
        var cards = run(5, 5, 5);
        assertThat(cards).extracting(ReviewCard::intervalDays).containsExactly(1, 6, 16);
        assertThat(cards.get(2).ease()).isCloseTo(2.8, within(1e-9));
    }

    @Test
    void aLapseRestartsTheIntervalsAndCostsEase() {
        var cards = run(4, 4, 4, 1, 4, 4, 4);
        assertThat(cards).extracting(ReviewCard::intervalDays).containsExactly(1, 6, 15, 1, 1, 6, 12);
        assertThat(cards.get(3).ease()).isCloseTo(1.96, within(1e-9));
        assertThat(cards.get(3).lapses()).isEqualTo(1);
        assertThat(cards.get(3).repetitions()).isZero();
    }

    @Test
    void easeNeverDropsBelowTheFloor() {
        var cards = run(0, 0, 0, 0);
        assertThat(cards.get(0).ease()).isCloseTo(1.7, within(1e-9));
        assertThat(cards.subList(1, cards.size())).allSatisfy(c -> assertThat(c.ease()).isEqualTo(ReviewCard.MIN_EASE));
        assertThat(cards).allSatisfy(c -> assertThat(c.lapses()).isZero());
    }

    @Test
    void mapsAttemptsToQuality() {
        assertThat(ReviewScheduler.quality(false, 1000, 30000)).isEqualTo(1);
        assertThat(ReviewScheduler.quality(true, 1000, 30000)).isEqualTo(4);
        assertThat(ReviewScheduler.quality(true, 45000, 30000)).isEqualTo(3);
    }

    @Test
    void rejectsQualityOutOfRange() {
        assertThatThrownBy(() -> sm2.next(ReviewCard.fresh(), 6)).isInstanceOf(IllegalArgumentException.class);
    }
}
