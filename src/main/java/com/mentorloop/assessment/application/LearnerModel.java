package com.mentorloop.assessment.application;

import java.util.List;
import java.util.UUID;

/**
 * The learner's current standing per concept, used to build quizzes. Defined here, implemented by the adaptive
 * module, so assessment does not depend on how mastery is estimated.
 */
public interface LearnerModel {

    record ConceptStanding(UUID conceptId, boolean unlocked, double pKnown) {
    }

    List<ConceptStanding> standings(UUID courseId, String learner);
}
