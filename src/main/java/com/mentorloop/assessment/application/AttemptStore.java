package com.mentorloop.assessment.application;

import com.mentorloop.assessment.domain.Answer;
import com.mentorloop.assessment.domain.Grade;
import com.mentorloop.assessment.domain.RaschCalibrator;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttemptStore {

    record Attempt(UUID id, UUID courseId, String learner, UUID itemId, String clientAttemptId, String requestHash,
                   long seq, UUID quizId, Answer answer, long latencyMs, Grade grade, Instant createdAt,
                   Instant gradedAt, String gradedBy) {
    }

    void insert(Attempt a);

    Optional<Attempt> find(String learner, UUID itemId, String clientAttemptId);

    Optional<Attempt> lock(UUID id);

    Optional<Attempt> byId(UUID id);

    void grade(UUID id, Grade grade, String by, Instant at);

    List<Attempt> needingReview(UUID courseId, int limit);

    /** Latest attempt per item for a quiz. */
    List<Attempt> latestForQuiz(UUID quizId);

    /** Each learner's first graded answer to each item of the course, for calibration. */
    List<RaschCalibrator.Response> firstResponses(UUID courseId);

    record ItemStat(UUID itemId, int attempts, int correct, int needsReview) {
    }

    List<ItemStat> itemStats(UUID courseId);

    List<UUID> coursesWithAttempts();
}
