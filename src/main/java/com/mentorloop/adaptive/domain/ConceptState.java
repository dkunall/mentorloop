package com.mentorloop.adaptive.domain;

/**
 * Where one learner stands on one concept. Derived from facts, never stored, so it cannot drift from them:
 * <pre>
 * LOCKED -> UNLOCKED      every prerequisite mastered
 * UNLOCKED -> LEARNING    first graded attempt
 * LEARNING -> MASTERED    p_known reaches the threshold
 * MASTERED -> REVIEW_DUE  a review card for one of its items is due
 * REVIEW_DUE -> MASTERED  the review was recalled (card rescheduled, p_known still above threshold)
 * REVIEW_DUE -> LEARNING  the review was missed and p_known fell below threshold
 * </pre>
 * Mastery is checked before the lock: a learner who mastered a concept keeps it even if a prerequisite's
 * estimate later dips.
 */
public enum ConceptState {
    LOCKED,
    UNLOCKED,
    LEARNING,
    MASTERED,
    REVIEW_DUE;

    public static ConceptState of(boolean prerequisitesMastered, int attempts, double pKnown, double threshold,
                                  boolean reviewDue) {
        if (attempts > 0 && pKnown >= threshold) {
            return reviewDue ? REVIEW_DUE : MASTERED;
        }
        if (!prerequisitesMastered) {
            return LOCKED;
        }
        return attempts == 0 ? UNLOCKED : LEARNING;
    }

    public boolean countsAsMastered() {
        return this == MASTERED || this == REVIEW_DUE;
    }
}
