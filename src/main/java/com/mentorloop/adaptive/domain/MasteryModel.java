package com.mentorloop.adaptive.domain;

/** Strategy for estimating how likely a learner knows a concept. */
public interface MasteryModel {

    /** P(known) after observing one graded answer, given P(known) before it. */
    double update(double pKnown, boolean correct, BktParams params);

    /** P(the next answer is right) given the current P(known). */
    double predictCorrect(double pKnown, BktParams params);
}
