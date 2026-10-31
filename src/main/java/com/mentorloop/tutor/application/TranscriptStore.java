package com.mentorloop.tutor.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TranscriptStore {

    record Message(long id, UUID courseId, String learner, String question, String answer, String mode,
                   String status, List<TutorService.Citation> citations, double coverage, String model,
                   long latencyMs, Instant at) {
    }

    long save(Message m);

    List<Message> recent(UUID courseId, String learner, int limit);

    int deleteOlderThan(Instant cutoff);

    /** Per-status counts for a course, for the instructor dashboard. */
    java.util.Map<String, Integer> statusCounts(UUID courseId);
}
