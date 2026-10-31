package com.mentorloop.assessment.application;

import com.mentorloop.assessment.application.AttemptStore.Attempt;
import com.mentorloop.assessment.domain.Grade;
import com.mentorloop.assessment.domain.ItemType;
import com.mentorloop.course.application.CourseService;
import com.mentorloop.shared.config.MentorLoopProperties;
import com.mentorloop.shared.domain.DomainRuleException;
import com.mentorloop.shared.error.ApiException;
import com.mentorloop.shared.security.Caller;
import com.mentorloop.shared.security.Role;
import com.mentorloop.tutor.application.QuizStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Graded quizzes. A quiz is drawn from the learner's unlocked concepts, weakest first, and per concept the items
 * whose difficulty is nearest a 70% expected success rate. While it is open the tutor only hints.
 */
@Service
public class QuizService implements QuizStatus {

    public record QuestionView(UUID itemId, ItemType type, String stem, List<String> options) {
    }

    public record ItemResult(UUID itemId, Grade.Outcome outcome, Boolean correct, Double score) {
    }

    public record QuizView(UUID id, UUID courseId, QuizStore.Status status, Instant openedAt, Instant closesAt,
                           List<QuestionView> questions, Double score, List<ItemResult> results) {
    }

    static final double TARGET_SUCCESS = 0.7;

    private final QuizStore quizzes;
    private final ItemStore items;
    private final AttemptStore attempts;
    private final CourseService courses;
    private final LearnerModel learners;
    private final MentorLoopProperties.Quiz cfg;
    private final Clock clock;

    QuizService(QuizStore quizzes, ItemStore items, AttemptStore attempts, CourseService courses, LearnerModel learners,
                MentorLoopProperties props, Clock clock) {
        this.quizzes = quizzes;
        this.items = items;
        this.attempts = attempts;
        this.courses = courses;
        this.learners = learners;
        this.cfg = props.quiz();
        this.clock = clock;
    }

    /** Starting while a quiz is open returns that quiz. */
    @Transactional
    public QuizView start(Caller caller, UUID courseId) {
        courses.lockLearner(caller, courseId);
        Instant now = clock.instant();
        Optional<QuizStore.Quiz> open = quizzes.open(courseId, caller.subject());
        if (open.isPresent()) {
            if (now.isBefore(open.get().closesAt())) {
                return view(open.get());
            }
            quizzes.close(open.get().id(), QuizStore.Status.EXPIRED, now, score(open.get()));
        }
        List<UUID> picked = pick(courseId, caller.subject());
        if (picked.isEmpty()) {
            throw new DomainRuleException("nothing-to-quiz", "No live items in your unlocked concepts yet");
        }
        var q = new QuizStore.Quiz(UUID.randomUUID(), courseId, caller.subject(), picked, QuizStore.Status.OPEN, now,
                now.plus(cfg.timeLimit()), null, null);
        quizzes.insert(q);
        return view(q);
    }

    @Transactional
    public QuizView submit(Caller caller, UUID quizId) {
        QuizStore.Quiz q = quizzes.lock(quizId).filter(x -> x.learner().equals(caller.subject()))
                .orElseThrow(() -> ApiException.notFound("Quiz"));
        if (q.status() != QuizStore.Status.OPEN) {
            return view(q);
        }
        Instant now = clock.instant();
        QuizStore.Status to = now.isBefore(q.closesAt()) ? QuizStore.Status.SUBMITTED : QuizStore.Status.EXPIRED;
        quizzes.close(quizId, to, now, score(q));
        return view(quizzes.byId(quizId).orElseThrow());
    }

    public QuizView get(Caller caller, UUID quizId) {
        QuizStore.Quiz q = quizzes.byId(quizId).orElseThrow(() -> ApiException.notFound("Quiz"));
        if (!q.learner().equals(caller.subject())) {
            if (caller.role() == Role.LEARNER) {
                throw ApiException.notFound("Quiz");
            }
            courses.requireOwned(caller, q.courseId());
        }
        return view(q);
    }

    @Transactional
    public int expireOverdue() {
        Instant now = clock.instant();
        var overdue = quizzes.lockOverdue(now, 500);
        overdue.forEach(q -> quizzes.close(q.id(), QuizStore.Status.EXPIRED, q.closesAt(), score(q)));
        return overdue.size();
    }

    /** For the tutor's quiz guard. */
    @Override
    public Optional<OpenQuiz> openQuiz(UUID courseId, String learner) {
        Instant now = clock.instant();
        return quizzes.open(courseId, learner).filter(q -> now.isBefore(q.closesAt())).map(q -> {
            var keys = items.keys(q.itemIds());
            List<String> revealing = new ArrayList<>();
            for (Item i : items.byIds(q.itemIds())) {
                revealing.addAll(keys.get(i.id()).revealingStrings(i.options()));
            }
            return new OpenQuiz(q.id(), List.copyOf(revealing));
        });
    }

    List<UUID> pick(UUID courseId, String learner) {
        Map<UUID, LearnerModel.ConceptStanding> standing = new LinkedHashMap<>();
        learners.standings(courseId, learner).stream().filter(LearnerModel.ConceptStanding::unlocked)
                .sorted(Comparator.comparingDouble(LearnerModel.ConceptStanding::pKnown))
                .forEach(s -> standing.put(s.conceptId(), s));
        Map<UUID, Deque<Item>> perConcept = new LinkedHashMap<>();
        standing.keySet().forEach(c -> perConcept.put(c, new ArrayDeque<>()));
        Map<UUID, List<Item>> grouped = new HashMap<>();
        for (Item i : items.items(courseId, com.mentorloop.assessment.domain.ItemStatus.LIVE)) {
            UUID primary = i.conceptIds().getFirst();
            if (standing.containsKey(primary)) {
                grouped.computeIfAbsent(primary, k -> new ArrayList<>()).add(i);
            }
        }
        grouped.forEach((c, list) -> {
            double target = logit(standing.get(c).pKnown()) - logit(TARGET_SUCCESS);
            list.sort(Comparator.comparingDouble((Item i) -> Math.abs(i.difficulty() - target)).thenComparing(Item::id));
            perConcept.get(c).addAll(list);
        });
        List<UUID> out = new ArrayList<>();
        boolean progress = true;
        while (out.size() < cfg.size() && progress) {
            progress = false;
            for (Deque<Item> d : perConcept.values()) {
                if (out.size() < cfg.size() && !d.isEmpty()) {
                    out.add(d.poll().id());
                    progress = true;
                }
            }
        }
        return out;
    }

    private Double score(QuizStore.Quiz q) {
        Map<UUID, Attempt> latest = new HashMap<>();
        attempts.latestForQuiz(q.id()).forEach(a -> latest.put(a.itemId(), a));
        double sum = 0;
        for (UUID item : q.itemIds()) {
            Attempt a = latest.get(item);
            if (a != null && a.grade().outcome() == Grade.Outcome.GRADED) {
                sum += a.grade().score();
            }
        }
        return sum / q.itemIds().size();
    }

    private QuizView view(QuizStore.Quiz q) {
        Map<UUID, Item> byId = new HashMap<>();
        items.byIds(q.itemIds()).forEach(i -> byId.put(i.id(), i));
        List<QuestionView> questions = q.itemIds().stream().map(byId::get)
                .map(i -> new QuestionView(i.id(), i.type(), i.stem(), i.options())).toList();
        List<ItemResult> results = null;
        if (q.status() != QuizStore.Status.OPEN) {
            Map<UUID, Attempt> latest = new HashMap<>();
            attempts.latestForQuiz(q.id()).forEach(a -> latest.put(a.itemId(), a));
            results = q.itemIds().stream().map(id -> {
                Attempt a = latest.get(id);
                return a == null ? new ItemResult(id, null, null, null)
                        : new ItemResult(id, a.grade().outcome(), a.grade().correct(), a.grade().score());
            }).toList();
        }
        return new QuizView(q.id(), q.courseId(), q.status(), q.openedAt(), q.closesAt(), questions, q.score(), results);
    }

    static double logit(double p) {
        double c = Math.min(1 - 1e-4, Math.max(1e-4, p));
        return Math.log(c / (1 - c));
    }
}
