package com.mentorloop.adaptive.domain;

/**
 * SM-2 state for one learner and one item.
 *
 * @param repetitions  successful reviews in a row
 * @param intervalDays days until the next review
 * @param ease         SM-2 easiness factor, never below 1.3
 * @param lapses       times the learner forgot after having learned it
 */
public record ReviewCard(int repetitions, int intervalDays, double ease, int lapses) {

    public static final double START_EASE = 2.5;
    public static final double MIN_EASE = 1.3;

    public static ReviewCard fresh() {
        return new ReviewCard(0, 0, START_EASE, 0);
    }
}
