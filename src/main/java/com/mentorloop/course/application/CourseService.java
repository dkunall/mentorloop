package com.mentorloop.course.application;

import com.mentorloop.course.domain.ConceptGraph;
import com.mentorloop.course.application.CourseViews.Concept;
import com.mentorloop.course.application.CourseViews.Course;
import com.mentorloop.course.application.CourseViews.Enrollment;
import com.mentorloop.course.application.CourseViews.Lesson;
import com.mentorloop.course.application.CourseViews.Module;
import com.mentorloop.course.application.CourseViews.Outline;
import com.mentorloop.shared.config.MentorLoopProperties;
import com.mentorloop.shared.domain.DomainConflictException;
import com.mentorloop.shared.domain.DomainRuleException;
import com.mentorloop.shared.error.ApiException;
import com.mentorloop.shared.security.Caller;
import com.mentorloop.shared.security.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class CourseService {

    public record ConceptIn(String key, String name, List<String> prerequisites) {
    }

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");
    private static final int MAX_BODY_CHARS = 60_000;

    private final CourseStore store;
    private final LessonIndexer indexer;
    private final Clock clock;
    private final MentorLoopProperties props;

    CourseService(CourseStore store, LessonIndexer indexer, Clock clock, MentorLoopProperties props) {
        this.store = store;
        this.indexer = indexer;
        this.clock = clock;
        this.props = props;
    }

    @Transactional
    public Course create(Caller caller, String title) {
        caller.requireStaff();
        requireText(title, "title", 200);
        var c = new Course(UUID.randomUUID(), title.strip(), caller.subject(), false, clock.instant());
        store.insertCourse(c);
        return c;
    }

    /**
     * Defines the course's concepts and prerequisite edges in one go, once. The graph is checked (unknown keys,
     * self-loops, cycles) before anything is stored.
     */
    @Transactional
    public List<Concept> defineConcepts(Caller caller, UUID courseId, List<ConceptIn> in) {
        Course course = ownedLocked(caller, courseId);
        if (course.published()) {
            throw new DomainConflictException("course-published", "A published course's concepts can't change");
        }
        if (!store.concepts(courseId).isEmpty()) {
            throw new DomainConflictException("concepts-defined", "This course's concepts are already defined");
        }
        if (in == null || in.isEmpty() || in.size() > 200) {
            throw new DomainRuleException("bad-concepts", "Give between 1 and 200 concepts");
        }
        Map<String, UUID> ids = new LinkedHashMap<>();
        for (ConceptIn c : in) {
            if (c.key() == null || !KEY.matcher(c.key()).matches()) {
                throw new DomainRuleException("bad-concept-key", "Concept keys are lowercase letters, digits and '-'");
            }
            requireText(c.name(), "concept name", 200);
            if (ids.putIfAbsent(c.key(), UUID.randomUUID()) != null) {
                throw new DomainRuleException("duplicate-concept", "Concept key " + c.key() + " appears twice");
            }
        }
        List<ConceptGraph.Edge> edges = new ArrayList<>();
        for (ConceptIn c : in) {
            for (String p : c.prerequisites() == null ? List.<String>of() : new HashSet<>(c.prerequisites())) {
                UUID pid = ids.get(p);
                if (pid == null) {
                    throw new DomainRuleException("unknown-prerequisite", c.key() + " needs unknown concept " + p);
                }
                edges.add(new ConceptGraph.Edge(pid, ids.get(c.key())));
            }
        }
        try {
            new ConceptGraph(List.copyOf(ids.values()), edges);
        } catch (DomainRuleException e) {
            Map<UUID, String> keys = new HashMap<>();
            ids.forEach((k, v) -> keys.put(v, k));
            String msg = e.getMessage();
            for (var en : keys.entrySet()) {
                msg = msg.replace(en.getKey().toString(), en.getValue());
            }
            throw new DomainRuleException(e.code(), msg);
        }
        List<Concept> concepts = new ArrayList<>();
        int pos = 0;
        for (ConceptIn c : in) {
            concepts.add(new Concept(ids.get(c.key()), c.key(), c.name().strip(), pos++,
                    c.prerequisites() == null ? List.of() : List.copyOf(new java.util.TreeSet<>(c.prerequisites()))));
        }
        store.insertConcepts(courseId, concepts, edges);
        return concepts;
    }

    @Transactional
    public Module addModule(Caller caller, UUID courseId, String title) {
        ownedLocked(caller, courseId);
        requireText(title, "title", 200);
        var m = new Module(UUID.randomUUID(), title.strip(), store.moduleCount(courseId));
        store.insertModule(courseId, m);
        return m;
    }

    /** Stores the lesson and indexes it for the tutor in the same transaction. */
    @Transactional
    public Lesson addLesson(Caller caller, UUID moduleId, String conceptKey, String title, String body, String videoUrl) {
        UUID courseId = store.courseOfModule(moduleId).orElseThrow(() -> ApiException.notFound("Module"));
        ownedLocked(caller, courseId);
        requireText(title, "title", 200);
        requireText(body, "body", MAX_BODY_CHARS);
        if (videoUrl != null && !videoUrl.isBlank()) {
            URI u;
            try {
                u = URI.create(videoUrl);
            } catch (DomainRuleException e) {
                throw new DomainRuleException("bad-video-url", "videoUrl is not a URL");
            }
            if (!"https".equals(u.getScheme())) {
                throw new DomainRuleException("bad-video-url", "videoUrl must be https");
            }
        }
        UUID conceptId = store.concepts(courseId).stream().filter(c -> c.key().equals(conceptKey)).map(Concept::id)
                .findFirst().orElseThrow(() -> new DomainRuleException("unknown-concept", "No concept " + conceptKey));
        var lesson = new Lesson(UUID.randomUUID(), moduleId, conceptId, title.strip(), body,
                videoUrl == null || videoUrl.isBlank() ? null : videoUrl, store.lessonCount(moduleId));
        store.insertLesson(courseId, lesson);
        indexer.index(courseId, lesson);
        return lesson;
    }

    @Transactional
    public Course publish(Caller caller, UUID courseId) {
        Course c = ownedLocked(caller, courseId);
        if (store.concepts(courseId).isEmpty() || store.lessons(courseId).isEmpty()) {
            throw new DomainRuleException("course-incomplete", "A course needs concepts and lessons to publish");
        }
        store.setPublished(courseId);
        return new Course(c.id(), c.title(), c.owner(), true, c.createdAt());
    }

    /** Learners enroll themselves in published courses. Enrolling twice returns the existing enrollment. */
    @Transactional
    public Enrollment enroll(Caller caller, UUID courseId, String cohort) {
        if (caller.role() != Role.LEARNER) {
            throw ApiException.forbidden("learners-only", "Only learners enroll");
        }
        Course c = store.course(courseId).orElseThrow(() -> ApiException.notFound("Course"));
        if (!c.published()) {
            throw ApiException.notFound("Course");
        }
        if (cohort != null && cohort.length() > 64) {
            throw ApiException.invalid("cohort is at most 64 characters");
        }
        var e = new Enrollment(courseId, caller.subject(), emailOf(caller.subject()), cohort, clock.instant());
        store.enroll(e);
        return store.enrollment(courseId, caller.subject()).orElseThrow();
    }

    public Outline outline(Caller caller, UUID courseId) {
        Course c = visible(caller, courseId);
        return new Outline(c, store.concepts(courseId), store.modules(courseId), store.lessons(courseId));
    }

    // ---------- queries for other modules ----------

    public Course visible(Caller caller, UUID courseId) {
        Course c = store.course(courseId).orElseThrow(() -> ApiException.notFound("Course"));
        if (caller.isStaff()) {
            if (caller.role() == Role.INSTRUCTOR && !c.owner().equals(caller.subject())) {
                throw ApiException.notFound("Course");
            }
            return c;
        }
        if (!c.published() || store.enrollment(courseId, caller.subject()).isEmpty()) {
            throw ApiException.notFound("Course");
        }
        return c;
    }

    /** For learner-side endpoints: the caller must be a learner enrolled in the course. */
    public Enrollment requireEnrolled(Caller caller, UUID courseId) {
        if (caller.role() != Role.LEARNER) {
            throw ApiException.forbidden("learners-only", "This is a learner endpoint");
        }
        return store.enrollment(courseId, caller.subject()).orElseThrow(() -> ApiException.notFound("Course"));
    }

    public Course requireOwned(Caller caller, UUID courseId) {
        Course c = store.course(courseId).orElseThrow(() -> ApiException.notFound("Course"));
        caller.requireOwnerOf(c.owner());
        return c;
    }

    public ConceptGraph graph(UUID courseId) {
        return new ConceptGraph(store.concepts(courseId).stream().map(Concept::id).toList(), store.edges(courseId));
    }

    public List<Concept> concepts(UUID courseId) {
        return store.concepts(courseId);
    }

    public List<Lesson> lessons(UUID courseId) {
        return store.lessons(courseId);
    }

    public Lesson lesson(UUID lessonId) {
        return store.lesson(lessonId).orElseThrow(() -> ApiException.notFound("Lesson"));
    }

    public UUID courseOfLesson(UUID lessonId) {
        return store.courseOfLesson(lessonId).orElseThrow(() -> ApiException.notFound("Lesson"));
    }

    public List<Enrollment> enrollments(UUID courseId) {
        return store.enrollments(courseId);
    }

    public java.util.Optional<Enrollment> enrollment(UUID courseId, String learner) {
        return store.enrollment(courseId, learner);
    }

    public java.util.Optional<Course> course(UUID courseId) {
        return store.course(courseId);
    }

    /**
     * Locks the caller's enrollment row for the rest of the transaction: everything one learner does that must be
     * ordered (attempts, quiz start) goes through this lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Enrollment lockLearner(Caller caller, UUID courseId) {
        if (caller.role() != Role.LEARNER) {
            throw ApiException.forbidden("learners-only", "This is a learner endpoint");
        }
        return store.lockEnrollment(courseId, caller.subject()).orElseThrow(() -> ApiException.notFound("Course"));
    }

    /**
     * Next per-learner attempt number. Must run inside the attempt's transaction: it holds the learner's
     * enrollment row lock until commit, so one learner's attempts get numbers in commit order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long claimSeq(UUID courseId, String learner) {
        return store.claimSeq(courseId, learner).orElseThrow(() -> ApiException.notFound("Course"));
    }

    String emailOf(String subject) {
        return subject.contains("@") ? subject : subject + "@" + props.mail().learnerDomain();
    }

    /** Row-locks the course for the rest of the caller's transaction, so concurrent writers to it run one at a time. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID courseId) {
        store.lockCourse(courseId).orElseThrow(() -> ApiException.notFound("Course"));
    }

    private Course ownedLocked(Caller caller, UUID courseId) {
        Course c = store.lockCourse(courseId).orElseThrow(() -> ApiException.notFound("Course"));
        caller.requireOwnerOf(c.owner());
        return c;
    }

    private static void requireText(String s, String what, int max) {
        if (s == null || s.isBlank()) {
            throw ApiException.invalid(what + " is required");
        }
        if (s.length() > max) {
            throw ApiException.invalid(what + " is at most " + max + " characters");
        }
    }
}
