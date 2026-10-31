package com.mentorloop.adaptive.application;

import com.mentorloop.adaptive.domain.ConceptState;
import com.mentorloop.adaptive.domain.NextActivityRecommender;
import com.mentorloop.assessment.application.AttemptStore;
import com.mentorloop.assessment.application.Item;
import com.mentorloop.assessment.application.ItemStore;
import com.mentorloop.assessment.application.LearnerModel;
import com.mentorloop.assessment.domain.ItemStatus;
import com.mentorloop.course.application.CourseService;
import com.mentorloop.course.application.CourseViews;
import com.mentorloop.course.domain.ConceptGraph;
import com.mentorloop.shared.config.MentorLoopProperties;
import com.mentorloop.shared.error.ApiException;
import com.mentorloop.shared.security.Caller;
import com.mentorloop.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read side of the learner model: progress, the next activity, certificates, and the instructor dashboard. */
@Service
public class ProgressService implements LearnerModel {

    public record ConceptProgress(UUID conceptId, String key, String name, ConceptState state, double pKnown,
                                  int attempts, int correct, Instant masteredAt) {
    }

    public record Progress(UUID courseId, String learner, long appliedSeq, List<ConceptProgress> concepts,
                           int reviewsDue, String certificateCode) {
    }

    public record Next(NextActivityRecommender.Kind kind, String concept, UUID itemId, UUID lessonId, String reason) {
    }

    public record CertificateView(String code, String courseTitle, String learner, Instant issuedAt) {
    }

    public record ItemRow(UUID itemId, String stem, String status, double difficulty, int difficultyVersion,
                          int attempts, double correctRate, int needingReview) {
    }

    public record Dashboard(UUID courseId, int enrolled, List<ConceptRow> concepts, List<ItemRow> items,
                            int eventsWaiting) {
    }

    public record ConceptRow(String key, int learnersStarted, int mastered, double meanPKnown) {
    }

    private final ProgressStore store;
    private final DueReviews due;
    private final CourseService courses;
    private final ItemStore items;
    private final AttemptStore attempts;
    private final MentorLoopProperties props;
    private final Clock clock;
    private final NextActivityRecommender recommender = new NextActivityRecommender();

    ProgressService(ProgressStore store, DueReviews due, CourseService courses, ItemStore items, AttemptStore attempts,
                    MentorLoopProperties props, Clock clock) {
        this.store = store;
        this.due = due;
        this.courses = courses;
        this.items = items;
        this.attempts = attempts;
        this.props = props;
        this.clock = clock;
    }

    /** Learners read their own progress; the course's instructor can read anyone's. */
    public Progress progress(Caller caller, UUID courseId, String learner) {
        String who = subjectFor(caller, courseId, learner);
        Instant now = clock.instant();
        List<CourseViews.Concept> concepts = courses.concepts(courseId);
        Map<UUID, ConceptState> states = states(courseId, who, now);
        Map<UUID, ProgressStore.Mastery> mastery = store.mastery(courseId, who);
        double pInit = props.mastery().pInit();
        List<ConceptProgress> rows = concepts.stream().map(c -> {
            var m = mastery.get(c.id());
            return new ConceptProgress(c.id(), c.key(), c.name(), states.get(c.id()), m == null ? pInit : m.pKnown(),
                    m == null ? 0 : m.attempts(), m == null ? 0 : m.correct(), m == null ? null : m.masteredAt());
        }).toList();
        return new Progress(courseId, who, store.appliedSeq(courseId, who), rows, due.due(courseId, who, now).size(),
                store.certificateOf(courseId, who).map(ProgressStore.Certificate::code).orElse(null));
    }

    public Next next(Caller caller, UUID courseId) {
        courses.requireEnrolled(caller, courseId);
        String who = caller.subject();
        Instant now = clock.instant();
        List<CourseViews.Concept> concepts = courses.concepts(courseId);
        ConceptGraph graph = courses.graph(courseId);
        Map<UUID, ConceptState> states = states(courseId, who, now);
        Map<UUID, ProgressStore.Mastery> mastery = store.mastery(courseId, who);
        Set<UUID> read = store.lessonsRead(courseId, who);
        Map<UUID, UUID> firstLesson = new HashMap<>();
        courses.lessons(courseId).stream().sorted(Comparator.comparingInt(CourseViews.Lesson::pos))
                .forEach(l -> firstLesson.putIfAbsent(l.conceptId(), l.id()));
        Map<UUID, String> keyOf = new HashMap<>();
        concepts.forEach(c -> keyOf.put(c.id(), c.key()));
        List<NextActivityRecommender.ConceptView> curriculum = new ArrayList<>();
        for (UUID id : graph.order()) {
            var m = mastery.get(id);
            UUID lesson = firstLesson.get(id);
            curriculum.add(new NextActivityRecommender.ConceptView(id, states.get(id),
                    m == null ? props.mastery().pInit() : m.pKnown(), m == null ? 0 : m.attempts(),
                    lesson != null && read.contains(lesson), Optional.ofNullable(lesson)));
        }
        Set<UUID> right = store.itemsAnsweredCorrectly(courseId, who);
        Map<UUID, List<NextActivityRecommender.ItemView>> pool = new HashMap<>();
        for (Item i : items.items(courseId, ItemStatus.LIVE)) {
            UUID c = i.conceptIds().getFirst();
            pool.computeIfAbsent(c, k -> new ArrayList<>())
                    .add(new NextActivityRecommender.ItemView(i.id(), c, i.difficulty(), right.contains(i.id())));
        }
        Set<UUID> live = new HashSet<>();
        pool.values().forEach(l -> l.forEach(v -> live.add(v.id())));
        List<NextActivityRecommender.DueReview> dueList = due.due(courseId, who, now).stream()
                .filter(d -> live.contains(d.itemId()))
                .map(d -> new NextActivityRecommender.DueReview(d.itemId(), d.conceptId(), d.dueAt())).toList();
        var a = recommender.next(curriculum, dueList, pool);
        return new Next(a.kind(), a.conceptId() == null ? null : keyOf.get(a.conceptId()), a.itemId(), a.lessonId(),
                a.reason());
    }

    @Transactional
    public boolean completeLesson(Caller caller, UUID lessonId) {
        UUID courseId = courses.courseOfLesson(lessonId);
        courses.requireEnrolled(caller, courseId);
        return store.markLessonRead(courseId, caller.subject(), lessonId, clock.instant());
    }

    public CertificateView certificate(String code) {
        var c = store.certificate(code).orElseThrow(() -> ApiException.notFound("Certificate"));
        return new CertificateView(c.code(), c.title(), c.learner(), c.issuedAt());
    }

    public Dashboard dashboard(Caller caller, UUID courseId) {
        courses.requireOwned(caller, courseId);
        Map<UUID, String> keyOf = new HashMap<>();
        courses.concepts(courseId).forEach(c -> keyOf.put(c.id(), c.key()));
        List<ConceptRow> concepts = store.conceptStats(courseId, props.mastery().threshold()).stream()
                .map(s -> new ConceptRow(keyOf.get(s.conceptId()), s.learners(), s.mastered(), s.meanPKnown()))
                .toList();
        Map<UUID, AttemptStore.ItemStat> stats = new HashMap<>();
        attempts.itemStats(courseId).forEach(s -> stats.put(s.itemId(), s));
        List<ItemRow> rows = items.items(courseId, null).stream().map(i -> {
            var s = stats.get(i.id());
            int n = s == null ? 0 : s.attempts();
            return new ItemRow(i.id(), i.stem(), i.status().name(), i.difficulty(), i.difficultyVersion(), n,
                    n == 0 ? 0 : (double) s.correct() / n, s == null ? 0 : s.needsReview());
        }).toList();
        return new Dashboard(courseId, courses.enrollments(courseId).size(), concepts, rows,
                store.pendingEvents(courseId));
    }

    @Override
    public List<ConceptStanding> standings(UUID courseId, String learner) {
        Map<UUID, ConceptState> states = states(courseId, learner, clock.instant());
        Map<UUID, ProgressStore.Mastery> mastery = store.mastery(courseId, learner);
        return courses.concepts(courseId).stream().map(c -> {
            var m = mastery.get(c.id());
            return new ConceptStanding(c.id(), states.get(c.id()) != ConceptState.LOCKED,
                    m == null ? props.mastery().pInit() : m.pKnown());
        }).toList();
    }

    Map<UUID, ConceptState> states(UUID courseId, String learner, Instant now) {
        ConceptGraph graph = courses.graph(courseId);
        Map<UUID, ProgressStore.Mastery> mastery = store.mastery(courseId, learner);
        double threshold = props.mastery().threshold();
        Set<UUID> dueConcepts = new HashSet<>();
        due.due(courseId, learner, now).forEach(d -> dueConcepts.add(d.conceptId()));
        Map<UUID, ConceptState> out = new HashMap<>();
        Set<UUID> mastered = new HashSet<>();
        for (UUID id : graph.order()) {
            var m = mastery.get(id);
            int n = m == null ? 0 : m.attempts();
            double p = m == null ? props.mastery().pInit() : m.pKnown();
            ConceptState s = ConceptState.of(graph.unlocked(id, mastered), n, p, threshold, dueConcepts.contains(id));
            out.put(id, s);
            if (s.countsAsMastered()) {
                mastered.add(id);
            }
        }
        return out;
    }

    private String subjectFor(Caller caller, UUID courseId, String learner) {
        if (caller.role() == Role.LEARNER) {
            if (learner != null && !learner.equals(caller.subject())) {
                throw ApiException.forbidden("forbidden", "Learners can only read their own progress");
            }
            courses.requireEnrolled(caller, courseId);
            return caller.subject();
        }
        courses.requireOwned(caller, courseId);
        if (learner == null) {
            throw ApiException.invalid("learner is required");
        }
        courses.enrollment(courseId, learner).orElseThrow(() -> ApiException.notFound("Enrollment"));
        return learner;
    }
}
