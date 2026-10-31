package com.mentorloop.adaptive.application;

import com.mentorloop.adaptive.domain.BktModel;
import com.mentorloop.adaptive.domain.BktParams;
import com.mentorloop.adaptive.domain.MasteryModel;
import com.mentorloop.adaptive.domain.ReviewCard;
import com.mentorloop.adaptive.domain.ReviewScheduler;
import com.mentorloop.adaptive.domain.Sm2Scheduler;
import com.mentorloop.course.application.CourseService;
import com.mentorloop.outbox.application.Outbox;
import com.mentorloop.outbox.application.ProcessedEvents;
import com.mentorloop.shared.config.MentorLoopProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns attempts into mastery estimates and review schedules.
 * <p>
 * Kafka delivers at least once and, across a rebalance, possibly out of order, so the event is first stored and
 * then applied strictly in the learner's attempt order: under the learner's progress row lock, events are
 * applied from {@code applied_seq + 1} for as long as the sequence is contiguous. An event that arrives early
 * waits in practice_event until the gap is filled. Duplicates are no-ops. BKT is order-sensitive, so this is
 * what makes the estimate the same however the events were delivered.
 * <p>
 * The exception is a manual grade: it is applied when it arrives, after attempts the learner made later.
 */
@Service
public class PracticeService {

    private static final String CONSUMER = "adaptive";
    private static final int BATCH = 500;

    private final ProgressStore store;
    private final DueReviews due;
    private final CourseService courses;
    private final Outbox outbox;
    private final ProcessedEvents processed;
    private final MentorLoopProperties props;
    private final Clock clock;
    private final MeterRegistry meters;
    private final MasteryModel bkt = new BktModel();
    private final ReviewScheduler sm2 = new Sm2Scheduler();
    private final SecureRandom random = new SecureRandom();

    PracticeService(ProgressStore store, DueReviews due, CourseService courses, Outbox outbox,
                    ProcessedEvents processed, MentorLoopProperties props, Clock clock, MeterRegistry meters) {
        this.store = store;
        this.due = due;
        this.courses = courses;
        this.outbox = outbox;
        this.processed = processed;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    /** @return how many events were applied (0 for a duplicate or an event waiting for an earlier one) */
    @Transactional
    public int recorded(PracticeEvent e) {
        if (!store.insertEvent(e)) {
            meters.counter("mentorloop.practice.duplicates").increment();
        }
        long applied = store.lockProgress(e.courseId(), e.learner());
        if (e.seq() <= applied) {
            return 0;
        }
        Instant now = clock.instant();
        long next = applied + 1;
        int n = 0;
        for (PracticeEvent p : store.pending(e.courseId(), e.learner(), next, BATCH)) {
            if (p.seq() != next) {
                break;
            }
            apply(p, now);
            next++;
            n++;
        }
        if (n == 0) {
            meters.counter("mentorloop.practice.waiting").increment();
            return 0;
        }
        store.setAppliedSeq(e.courseId(), e.learner(), next - 1);
        afterChange(e.courseId(), e.learner(), now);
        return n;
    }

    /** @param graded the attempt as it stands after the manual grade */
    @Transactional
    public boolean regraded(UUID eventId, PracticeEvent graded) {
        if (!processed.markProcessed(CONSUMER, eventId)) {
            return false;
        }
        // Normally AttemptRecorded is stored already. If not, store this one; the late AttemptRecorded is then a
        // duplicate, and the attempt is applied in order with its final grade.
        store.insertEvent(graded);
        store.lockProgress(graded.courseId(), graded.learner());
        store.regrade(graded.attemptId(), graded.correct());
        if (!store.applied(graded.attemptId())) {
            return true;
        }
        Instant now = clock.instant();
        update(graded, now);
        meters.counter("mentorloop.practice.late_grades").increment();
        afterChange(graded.courseId(), graded.learner(), now);
        return true;
    }

    private void apply(PracticeEvent p, Instant now) {
        if (p.graded()) {
            update(p, now);
        }
        store.markApplied(p.attemptId(), now);
    }

    private void update(PracticeEvent p, Instant now) {
        var m = props.mastery();
        BktParams params = new BktParams(m.pInit(), m.pLearn(), m.pSlip(), m.pGuess());
        Map<UUID, ProgressStore.Mastery> current = store.mastery(p.courseId(), p.learner());
        for (UUID concept : p.conceptIds()) {
            var before = current.getOrDefault(concept, new ProgressStore.Mastery(concept, m.pInit(), 0, 0, null));
            double pKnown = bkt.update(before.pKnown(), p.correct(), params);
            Instant masteredAt = before.masteredAt();
            if (masteredAt == null && pKnown >= m.threshold()) {
                masteredAt = now;
                outbox.append(Outbox.LEARNER_EVENTS, p.learner(), AdaptiveEvents.CONCEPT_MASTERED, Map.of(
                        "courseId", p.courseId().toString(), "learner", p.learner(), "conceptId", concept.toString(),
                        "pKnown", pKnown));
                meters.counter("mentorloop.concepts.mastered").increment();
            }
            store.saveMastery(p.courseId(), p.learner(), new ProgressStore.Mastery(concept, pKnown,
                    before.attempts() + 1, before.correct() + (p.correct() ? 1 : 0), masteredAt), now);
        }
        var before = store.card(p.courseId(), p.learner(), p.itemId())
                .map(ProgressStore.Card::card).orElse(ReviewCard.fresh());
        ReviewCard next = sm2.next(before, ReviewScheduler.quality(p.correct(), p.latencyMs(),
                props.reviews().slowMillis()));
        Duration day = props.reviews().dayLength();
        Instant dueAt = p.at().plus(day.multipliedBy(next.intervalDays()));
        store.saveCard(p.courseId(), p.learner(),
                new ProgressStore.Card(p.itemId(), p.conceptIds().getFirst(), next, dueAt), now);
    }

    private void afterChange(UUID courseId, String learner, Instant now) {
        issueCertificateIfDone(courseId, learner, now);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    due.invalidate(courseId, learner);
                }
            });
        }
    }

    private void issueCertificateIfDone(UUID courseId, String learner, Instant now) {
        List<UUID> concepts = courses.concepts(courseId).stream().map(c -> c.id()).toList();
        Map<UUID, ProgressStore.Mastery> mastery = store.mastery(courseId, learner);
        boolean all = !concepts.isEmpty()
                && concepts.stream().allMatch(c -> mastery.containsKey(c) && mastery.get(c).masteredAt() != null);
        if (!all || store.certificateOf(courseId, learner).isPresent()) {
            return;
        }
        var course = courses.course(courseId).orElseThrow();
        var cert = new ProgressStore.Certificate(code(), courseId, learner, course.title(), now);
        if (store.insertCertificate(cert)) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("code", cert.code());
            data.put("courseId", courseId.toString());
            data.put("learner", learner);
            data.put("title", course.title());
            data.put("email", courses.enrollment(courseId, learner).map(en -> en.email()).orElse(null));
            data.put("issuedAt", now.toString());
            outbox.append(Outbox.LEARNER_EVENTS, learner, AdaptiveEvents.CERTIFICATE_ISSUED, data);
            meters.counter("mentorloop.certificates.issued").increment();
        }
    }

    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private String code() {
        StringBuilder sb = new StringBuilder("ML-");
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
