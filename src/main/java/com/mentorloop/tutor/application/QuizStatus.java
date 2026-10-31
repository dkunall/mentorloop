package com.mentorloop.tutor.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What the tutor needs to know about a learner's open graded quiz. Defined here and implemented by the
 * assessment module, so the tutor never sees answer keys or item tables, only strings it must not say.
 */
public interface QuizStatus {

    record OpenQuiz(UUID quizId, List<String> revealing) {
    }

    Optional<OpenQuiz> openQuiz(UUID courseId, String learner);
}
