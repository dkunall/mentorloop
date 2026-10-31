package com.mentorloop.adaptive.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One attempt as the adaptive module sees it. {@code graded} is false while it waits for an instructor. */
public record PracticeEvent(UUID attemptId, UUID courseId, String learner, long seq, UUID itemId, List<UUID> conceptIds,
                            boolean graded, boolean correct, long latencyMs, Instant at) {
}
