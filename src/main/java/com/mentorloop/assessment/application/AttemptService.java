package com.mentorloop.assessment.application;

import com.mentorloop.assessment.application.AttemptStore.Attempt;
import com.mentorloop.assessment.domain.Answer;
import com.mentorloop.assessment.domain.AnswerKey;
import com.mentorloop.assessment.domain.Grade;
import com.mentorloop.assessment.domain.Graders;
import com.mentorloop.assessment.domain.ItemStatus;
import com.mentorloop.course.application.CourseService;
import com.mentorloop.outbox.application.Outbox;
import com.mentorloop.shared.domain.DomainConflictException;
import com.mentorloop.shared.error.ApiException;
import com.mentorloop.shared.security.Caller;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AttemptService {

    public record Result(UUID attemptId, long seq, UUID itemId, UUID quizId, Grade.Outcome outcome, boolean correct,
                         double score, String explanation, boolean replayed) {
    }

    private static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final long MAX_LATENCY_MS = 86_400_000L;

    private final ItemStore items;
    private final AttemptStore attempts;
    private final QuizStore quizzes;
    private final CourseService courses;
    private final Outbox outbox;
    private final Graders graders = Graders.standard();
    private final Clock clock;
    private final MeterRegistry meters;

    AttemptService(ItemStore items, AttemptStore attempts, QuizStore quizzes, CourseService courses, Outbox outbox,
                   Clock clock, MeterRegistry meters) {
        this.items = items;
        this.attempts = attempts;
        this.quizzes = quizzes;
        this.courses = courses;
        this.outbox = outbox;
        this.clock = clock;
        this.meters = meters;
    }

    /**
     * Grades one answer, once. The learner's enrollment row is locked for the transaction, so a learner's
     * attempts are numbered and committed one at a time, and a retry with the same {@code clientAttemptId}
     * always sees the first one: same answer gets the stored result back, a different answer is rejected.
     */
    @Transactional
    public Result submit(Caller caller, UUID itemId, String clientAttemptId, Answer answer, Long latencyMs, UUID quizId) {
        if (clientAttemptId == null || !CLIENT_ID.matcher(clientAttemptId).matches()) {
            throw ApiException.invalid("clientAttemptId is 1-64 of A-Z a-z 0-9 . _ : -");
        }
        if (latencyMs == null || latencyMs < 0 || latencyMs > MAX_LATENCY_MS) {
            throw ApiException.invalid("latencyMs is between 0 and 86400000");
        }
        if (answer == null) {
            throw ApiException.invalid("answer is required");
        }
        Item item = items.item(itemId).orElseThrow(() -> ApiException.notFound("Item"));
        courses.lockLearner(caller, item.courseId());
        String hash = hash(answer, quizId);
        Optional<Attempt> existing = attempts.find(caller.subject(), itemId, clientAttemptId);
        if (existing.isPresent()) {
            Attempt a = existing.get();
            if (!a.requestHash().equals(hash)) {
                throw ApiException.unprocessable("idempotency-mismatch",
                        "clientAttemptId " + clientAttemptId + " was already used with a different answer");
            }
            meters.counter("mentorloop.attempts.replayed").increment();
            return result(a, item, true);
        }
        if (item.status() != ItemStatus.LIVE) {
            throw ApiException.notFound("Item");
        }
        Instant now = clock.instant();
        UUID attachedQuiz = null;
        Optional<QuizStore.Quiz> open = quizzes.open(item.courseId(), caller.subject());
        if (open.isPresent() && !now.isBefore(open.get().closesAt())) {
            quizzes.close(open.get().id(), QuizStore.Status.EXPIRED, now, null);
            open = Optional.empty();
        }
        if (quizId != null) {
            if (open.isEmpty() || !open.get().id().equals(quizId)) {
                throw new DomainConflictException("quiz-closed", "That quiz is not open");
            }
            if (!open.get().itemIds().contains(itemId)) {
                throw new DomainConflictException("not-in-quiz", "That item is not part of the quiz");
            }
            attachedQuiz = quizId;
        } else if (open.isPresent() && open.get().itemIds().contains(itemId)) {
            attachedQuiz = open.get().id();
        }
        AnswerKey key = items.key(itemId);
        Grade grade = graders.grade(item.type(), key, answer, item.options().size());
        long seq = courses.claimSeq(item.courseId(), caller.subject());
        var a = new Attempt(UUID.randomUUID(), item.courseId(), caller.subject(), itemId, clientAttemptId, hash, seq,
                attachedQuiz, answer, latencyMs, grade, now, grade.outcome() == Grade.Outcome.GRADED ? now : null,
                grade.outcome() == Grade.Outcome.GRADED ? "auto" : null);
        attempts.insert(a);
        outbox.append(Outbox.ATTEMPT_EVENTS, caller.subject(), AssessmentEvents.ATTEMPT_RECORDED,
                AssessmentEvents.payload(a, item.conceptIds()));
        meters.counter("mentorloop.attempts", "outcome", grade.outcome().name()).increment();
        return result(a, item, false);
    }

    /** An instructor settles a NEEDS_REVIEW attempt. Mastery picks it up from the AttemptRegraded event. */
    @Transactional
    public Result gradeManually(Caller caller, UUID attemptId, Boolean correct, Double score) {
        Attempt a = attempts.lock(attemptId).orElseThrow(() -> ApiException.notFound("Attempt"));
        courses.requireOwned(caller, a.courseId());
        if (correct == null) {
            throw ApiException.invalid("correct is required");
        }
        double s = score == null ? (correct ? 1 : 0) : score;
        if (!(s >= 0 && s <= 1)) {
            throw ApiException.invalid("score is between 0 and 1");
        }
        if (a.grade().outcome() != Grade.Outcome.NEEDS_REVIEW) {
            throw new DomainConflictException("already-graded", "This attempt is already graded");
        }
        Grade g = new Grade(Grade.Outcome.GRADED, correct, s);
        Instant now = clock.instant();
        attempts.grade(attemptId, g, caller.subject(), now);
        Item item = items.item(a.itemId()).orElseThrow();
        Attempt graded = attempts.byId(attemptId).orElseThrow();
        outbox.append(Outbox.ATTEMPT_EVENTS, a.learner(), AssessmentEvents.ATTEMPT_REGRADED,
                AssessmentEvents.payload(graded, item.conceptIds()));
        return result(graded, item, false);
    }

    public List<Attempt> reviewQueue(Caller caller, UUID courseId) {
        courses.requireOwned(caller, courseId);
        return attempts.needingReview(courseId, 100);
    }

    private Result result(Attempt a, Item item, boolean replayed) {
        boolean showExplanation = a.quizId() == null && a.grade().outcome() == Grade.Outcome.GRADED;
        return new Result(a.id(), a.seq(), a.itemId(), a.quizId(), a.grade().outcome(), a.grade().correct(),
                a.grade().score(), showExplanation ? item.explanation() : null, replayed);
    }

    static String hash(Answer a, UUID quizId) {
        String canonical = a.choices().stream().sorted().map(String::valueOf).reduce((x, y) -> x + "," + y).orElse("")
                + "\u0000" + (a.number() == null ? "" : Double.toString(a.number()))
                + "\u0000" + (a.text() == null ? "" : a.text())
                + "\u0000" + (quizId == null ? "" : quizId.toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
