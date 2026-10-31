package com.mentorloop.assessment.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface QuizStore {

    enum Status { OPEN, SUBMITTED, EXPIRED }

    record Quiz(UUID id, UUID courseId, String learner, List<UUID> itemIds, Status status, Instant openedAt,
                Instant closesAt, Instant submittedAt, Double score) {
    }

    void insert(Quiz q);

    Optional<Quiz> open(UUID courseId, String learner);

    Optional<Quiz> lock(UUID id);

    Optional<Quiz> byId(UUID id);

    void close(UUID id, Status status, Instant at, Double score);

    /** OPEN quizzes past their deadline, locked, skipping ones another transaction holds. */
    List<Quiz> lockOverdue(Instant now, int limit);
}
