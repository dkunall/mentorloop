package com.mentorloop.adaptive.application;

import com.mentorloop.adaptive.domain.ReviewCard;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface ProgressStore {

    record Mastery(UUID conceptId, double pKnown, int attempts, int correct, Instant masteredAt) {
    }

    record Card(UUID itemId, UUID conceptId, ReviewCard card, Instant dueAt) {
    }

    record Certificate(String code, UUID courseId, String learner, String title, Instant issuedAt) {
    }

    record DueLearner(UUID courseId, String learner, int due) {
    }

    record ConceptStat(UUID conceptId, int learners, int mastered, double meanPKnown) {
    }

    /** @return false if this attempt was already stored */
    boolean insertEvent(PracticeEvent e);

    /** Ensures the learner's progress row exists, locks it, and returns the last applied seq. */
    long lockProgress(UUID courseId, String learner);

    void setAppliedSeq(UUID courseId, String learner, long seq);

    long appliedSeq(UUID courseId, String learner);

    /** Stored, not yet applied events with seq at or above {@code fromSeq}, in seq order. */
    List<PracticeEvent> pending(UUID courseId, String learner, long fromSeq, int limit);

    Optional<PracticeEvent> event(UUID attemptId);

    boolean applied(UUID attemptId);

    void markApplied(UUID attemptId, Instant at);

    /** Settles a NEEDS_REVIEW event after manual grading. */
    void regrade(UUID attemptId, boolean correct);

    Map<UUID, Mastery> mastery(UUID courseId, String learner);

    void saveMastery(UUID courseId, String learner, Mastery m, Instant at);

    Optional<Card> card(UUID courseId, String learner, UUID itemId);

    void saveCard(UUID courseId, String learner, Card c, Instant at);

    List<Card> cards(UUID courseId, String learner);

    Set<UUID> itemsAnsweredCorrectly(UUID courseId, String learner);

    /** @return false if it was already marked read */
    boolean markLessonRead(UUID courseId, String learner, UUID lessonId, Instant at);

    Set<UUID> lessonsRead(UUID courseId, String learner);

    /** @return false if the learner already has a certificate for the course */
    boolean insertCertificate(Certificate c);

    Optional<Certificate> certificate(String code);

    Optional<Certificate> certificateOf(UUID courseId, String learner);

    List<DueLearner> learnersWithDueReviews(Instant now, int limit);

    List<ConceptStat> conceptStats(UUID courseId, double threshold);

    int pendingEvents(UUID courseId);
}
