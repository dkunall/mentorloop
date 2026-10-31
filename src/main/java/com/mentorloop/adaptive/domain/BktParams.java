package com.mentorloop.adaptive.domain;

/**
 * Bayesian Knowledge Tracing parameters for one concept.
 *
 * @param pInit  P(known) before any practice
 * @param pLearn P(unknown becomes known) after one practice opportunity
 * @param pSlip  P(wrong answer | known)
 * @param pGuess P(right answer | not known)
 */
public record BktParams(double pInit, double pLearn, double pSlip, double pGuess) {

    public BktParams {
        check("pInit", pInit);
        check("pLearn", pLearn);
        check("pSlip", pSlip);
        check("pGuess", pGuess);
        // With slip + guess >= 1 a right answer would be evidence of NOT knowing; the model degenerates.
        if (pSlip + pGuess >= 1.0) {
            throw new IllegalArgumentException("pSlip + pGuess must be below 1");
        }
    }

    private static void check(String name, double p) {
        if (!(p > 0.0 && p < 1.0)) {
            throw new IllegalArgumentException(name + " must be strictly between 0 and 1");
        }
    }
}
