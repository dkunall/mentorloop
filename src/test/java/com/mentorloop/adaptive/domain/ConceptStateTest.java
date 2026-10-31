package com.mentorloop.adaptive.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConceptStateTest {

    @Test
    void stateMachineFollowsTheDocumentedTransitions() {
        double t = 0.95;
        assertThat(ConceptState.of(false, 0, 0.2, t, false)).isEqualTo(ConceptState.LOCKED);
        assertThat(ConceptState.of(true, 0, 0.2, t, false)).isEqualTo(ConceptState.UNLOCKED);
        assertThat(ConceptState.of(true, 1, 0.5, t, false)).isEqualTo(ConceptState.LEARNING);
        assertThat(ConceptState.of(true, 4, 0.96, t, false)).isEqualTo(ConceptState.MASTERED);
        assertThat(ConceptState.of(true, 4, 0.96, t, true)).isEqualTo(ConceptState.REVIEW_DUE);
        // A failed review that drops p_known below threshold puts the concept back to LEARNING.
        assertThat(ConceptState.of(true, 5, 0.8, t, true)).isEqualTo(ConceptState.LEARNING);
        // Mastery sticks even if a prerequisite's estimate dips later.
        assertThat(ConceptState.of(false, 4, 0.96, t, false)).isEqualTo(ConceptState.MASTERED);
    }
}
