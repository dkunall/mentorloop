package com.mentorloop.adaptive.domain;

/**
 * Standard four-parameter BKT (Corbett and Anderson, 1994). O(1) per answer: condition P(known) on the evidence
 * with Bayes' rule, then apply the learning transition.
 */
public final class BktModel implements MasteryModel {

    @Override
    public double update(double pKnown, boolean correct, BktParams p) {
        double known = clamp(pKnown);
        double posterior;
        if (correct) {
            double a = known * (1 - p.pSlip());
            posterior = a / (a + (1 - known) * p.pGuess());
        } else {
            double a = known * p.pSlip();
            posterior = a / (a + (1 - known) * (1 - p.pGuess()));
        }
        return clamp(posterior + (1 - posterior) * p.pLearn());
    }

    @Override
    public double predictCorrect(double pKnown, BktParams p) {
        double known = clamp(pKnown);
        return known * (1 - p.pSlip()) + (1 - known) * p.pGuess();
    }

    /** Keeps the estimate off exactly 0 or 1, where Bayes' rule can no longer move it. */
    private static double clamp(double p) {
        return Math.max(1e-6, Math.min(1 - 1e-6, p));
    }
}
