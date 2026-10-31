package com.mentorloop.adaptive.adapter;

import com.mentorloop.adaptive.application.DueReviews;
import com.mentorloop.adaptive.application.ProgressStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One sorted set per learner, member {@code itemId|conceptId}, score the due time in epoch millis. It is only a
 * cache: Postgres review_card is the truth, a miss rebuilds the set, and if Redis is down the due list is read
 * from Postgres directly.
 * <p>
 * Known race: a rebuild that read Postgres just before another transaction committed and invalidated can write
 * the older list back. The short TTL bounds how long that stale list lives.
 */
@Component
class RedisDueReviews implements DueReviews {

    private static final Logger log = LoggerFactory.getLogger(RedisDueReviews.class);
    private static final Duration TTL = Duration.ofMinutes(10);
    /** Member written into every rebuilt set so an empty due list is still a cache hit. */
    private static final String SENTINEL = "-";

    private final StringRedisTemplate redis;
    private final ProgressStore store;

    RedisDueReviews(StringRedisTemplate redis, ProgressStore store) {
        this.redis = redis;
        this.store = store;
    }

    static String key(UUID courseId, String learner) {
        return "ml:due:" + courseId + ":" + learner;
    }

    @Override
    public List<Due> due(UUID courseId, String learner, Instant now) {
        String key = key(courseId, learner);
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                rebuild(key, courseId, learner);
            }
            Set<ZSetOperations.TypedTuple<String>> hits = redis.opsForZSet()
                    .rangeByScoreWithScores(key, 0, now.toEpochMilli());
            if (hits == null) {
                return fromDb(courseId, learner, now);
            }
            return hits.stream().filter(t -> !SENTINEL.equals(t.getValue())).map(t -> {
                String[] parts = t.getValue().split("\\|");
                return new Due(UUID.fromString(parts[0]), UUID.fromString(parts[1]),
                        Instant.ofEpochMilli(t.getScore().longValue()));
            }).toList();
        } catch (RuntimeException e) {
            log.warn("due cache unavailable, reading Postgres: {}", e.toString());
            return fromDb(courseId, learner, now);
        }
    }

    @Override
    public void invalidate(UUID courseId, String learner) {
        try {
            redis.delete(key(courseId, learner));
        } catch (RuntimeException e) {
            log.warn("could not drop due cache for a learner; it expires within {}: {}", TTL, e.toString());
        }
    }

    private void rebuild(String key, UUID courseId, String learner) {
        Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>();
        tuples.add(ZSetOperations.TypedTuple.of(SENTINEL, (double) Long.MAX_VALUE));
        for (ProgressStore.Card c : store.cards(courseId, learner)) {
            tuples.add(ZSetOperations.TypedTuple.of(c.itemId() + "|" + c.conceptId(), (double) c.dueAt().toEpochMilli()));
        }
        redis.opsForZSet().add(key, tuples);
        redis.expire(key, TTL);
    }

    private List<Due> fromDb(UUID courseId, String learner, Instant now) {
        return store.cards(courseId, learner).stream().filter(c -> !c.dueAt().isAfter(now))
                .map(c -> new Due(c.itemId(), c.conceptId(), c.dueAt())).toList();
    }
}
