package com.mentorloop.assessment.domain;

/**
 * @param outcome GRADED, or NEEDS_REVIEW when only an instructor can tell
 * @param score   0..1; partial credit only for multi-answer MCQs
 */
public record Grade(Outcome outcome, boolean correct, double score) {

    public enum Outcome { GRADED, NEEDS_REVIEW }

    public static Grade right() {
        return new Grade(Outcome.GRADED, true, 1.0);
    }

    public static Grade wrong() {
        return new Grade(Outcome.GRADED, false, 0.0);
    }

    public static Grade needsReview() {
        return new Grade(Outcome.NEEDS_REVIEW, false, 0.0);
    }
}
