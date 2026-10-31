package com.mentorloop.adaptive.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port for a learner's due list. The Redis adapter keeps a sorted set per learner as a cache of review_card,
 * rebuilt from Postgres on a miss and dropped after every commit that changed a card.
 */
public interface DueReviews {

    record Due(UUID itemId, UUID conceptId, Instant dueAt) {
    }

    List<Due> due(UUID courseId, String learner, Instant now);

    void invalidate(UUID courseId, String learner);
}
