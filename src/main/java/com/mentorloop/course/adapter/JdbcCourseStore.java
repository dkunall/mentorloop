package com.mentorloop.course.adapter;

import com.mentorloop.course.domain.ConceptGraph;
import com.mentorloop.course.application.CourseStore;
import com.mentorloop.course.application.CourseViews.Concept;
import com.mentorloop.course.application.CourseViews.Course;
import com.mentorloop.course.application.CourseViews.Enrollment;
import com.mentorloop.course.application.CourseViews.Lesson;
import com.mentorloop.course.application.CourseViews.Module;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

@Repository
class JdbcCourseStore implements CourseStore {

    private static final RowMapper<Course> COURSE = (rs, n) -> new Course(rs.getObject("id", UUID.class),
            rs.getString("title"), rs.getString("owner"), rs.getBoolean("published"),
            rs.getTimestamp("created_at").toInstant());
    private static final RowMapper<Lesson> LESSON = (rs, n) -> new Lesson(rs.getObject("id", UUID.class),
            rs.getObject("module_id", UUID.class), rs.getObject("concept_id", UUID.class), rs.getString("title"),
            rs.getString("body"), rs.getString("video_url"), rs.getInt("pos"));
    private static final RowMapper<Enrollment> ENROLLMENT = (rs, n) -> new Enrollment(
            rs.getObject("course_id", UUID.class), rs.getString("learner"), rs.getString("email"),
            rs.getString("cohort"), rs.getTimestamp("enrolled_at").toInstant());

    private final JdbcClient db;

    JdbcCourseStore(JdbcClient db) {
        this.db = db;
    }

    @Override
    public void insertCourse(Course c) {
        db.sql("insert into course (id, title, owner, published, created_at) values (?, ?, ?, ?, ?)")
                .params(c.id(), c.title(), c.owner(), c.published(), Timestamp.from(c.createdAt())).update();
    }

    @Override
    public Optional<Course> course(UUID id) {
        return db.sql("select * from course where id = ?").param(id).query(COURSE).optional();
    }

    @Override
    public Optional<Course> lockCourse(UUID id) {
        return db.sql("select * from course where id = ? for update").param(id).query(COURSE).optional();
    }

    @Override
    public void setPublished(UUID id) {
        db.sql("update course set published = true where id = ?").param(id).update();
    }

    @Override
    public void insertConcepts(UUID courseId, List<Concept> concepts, List<ConceptGraph.Edge> edges) {
        for (Concept c : concepts) {
            db.sql("insert into concept (id, course_id, key, name, pos) values (?, ?, ?, ?, ?)")
                    .params(c.id(), courseId, c.key(), c.name(), c.pos()).update();
        }
        for (ConceptGraph.Edge e : edges) {
            db.sql("insert into concept_edge (course_id, prereq_id, concept_id) values (?, ?, ?)")
                    .params(courseId, e.prerequisite(), e.concept()).update();
        }
    }

    @Override
    public List<Concept> concepts(UUID courseId) {
        Map<UUID, String> keys = conceptKeys(courseId);
        Map<UUID, TreeSet<String>> prereqs = new HashMap<>();
        for (ConceptGraph.Edge e : edges(courseId)) {
            prereqs.computeIfAbsent(e.concept(), k -> new TreeSet<>()).add(keys.get(e.prerequisite()));
        }
        return db.sql("select id, key, name, pos from concept where course_id = ? order by pos").param(courseId)
                .query((rs, n) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new Concept(id, rs.getString("key"), rs.getString("name"), rs.getInt("pos"),
                            List.copyOf(prereqs.getOrDefault(id, new TreeSet<>())));
                }).list();
    }

    @Override
    public Map<UUID, String> conceptKeys(UUID courseId) {
        Map<UUID, String> keys = new LinkedHashMap<>();
        db.sql("select id, key from concept where course_id = ? order by pos").param(courseId)
                .query(rs -> {
                    keys.put(rs.getObject("id", UUID.class), rs.getString("key"));
                });
        return keys;
    }

    @Override
    public List<ConceptGraph.Edge> edges(UUID courseId) {
        return db.sql("select prereq_id, concept_id from concept_edge where course_id = ? order by prereq_id, concept_id")
                .param(courseId).query((rs, n) -> new ConceptGraph.Edge(rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class))).list();
    }

    @Override
    public void insertModule(UUID courseId, Module m) {
        db.sql("insert into module (id, course_id, title, pos) values (?, ?, ?, ?)")
                .params(m.id(), courseId, m.title(), m.pos()).update();
    }

    @Override
    public Optional<UUID> courseOfModule(UUID moduleId) {
        return db.sql("select course_id from module where id = ?").param(moduleId).query(UUID.class).optional();
    }

    @Override
    public int lessonCount(UUID moduleId) {
        return db.sql("select count(*) from lesson where module_id = ?").param(moduleId).query(Integer.class).single();
    }

    @Override
    public int moduleCount(UUID courseId) {
        return db.sql("select count(*) from module where course_id = ?").param(courseId).query(Integer.class).single();
    }

    @Override
    public void insertLesson(UUID courseId, Lesson l) {
        db.sql("""
                insert into lesson (id, course_id, module_id, concept_id, title, body, video_url, pos, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now())""")
                .params(l.id(), courseId, l.moduleId(), l.conceptId(), l.title(), l.body(), l.videoUrl(), l.pos())
                .update();
    }

    @Override
    public Optional<Lesson> lesson(UUID lessonId) {
        return db.sql("select * from lesson where id = ?").param(lessonId).query(LESSON).optional();
    }

    @Override
    public Optional<UUID> courseOfLesson(UUID lessonId) {
        return db.sql("select course_id from lesson where id = ?").param(lessonId).query(UUID.class).optional();
    }

    @Override
    public List<Module> modules(UUID courseId) {
        return db.sql("select id, title, pos from module where course_id = ? order by pos").param(courseId)
                .query((rs, n) -> new Module(rs.getObject("id", UUID.class), rs.getString("title"), rs.getInt("pos")))
                .list();
    }

    @Override
    public List<Lesson> lessons(UUID courseId) {
        return db.sql("""
                select l.* from lesson l join module m on m.id = l.module_id
                where l.course_id = ? order by m.pos, l.pos""").param(courseId).query(LESSON).list();
    }

    @Override
    public boolean enroll(Enrollment e) {
        return db.sql("""
                insert into enrollment (course_id, learner, email, cohort, enrolled_at) values (?, ?, ?, ?, ?)
                on conflict do nothing""")
                .params(e.courseId(), e.learner(), e.email(), e.cohort(), Timestamp.from(e.enrolledAt())).update() == 1;
    }

    @Override
    public Optional<Enrollment> enrollment(UUID courseId, String learner) {
        return db.sql("select * from enrollment where course_id = ? and learner = ?").params(courseId, learner)
                .query(ENROLLMENT).optional();
    }

    @Override
    public List<Enrollment> enrollments(UUID courseId) {
        return new ArrayList<>(db.sql("select * from enrollment where course_id = ? order by learner")
                .param(courseId).query(ENROLLMENT).list());
    }

    @Override
    public Optional<Enrollment> lockEnrollment(UUID courseId, String learner) {
        return db.sql("select * from enrollment where course_id = ? and learner = ? for update")
                .params(courseId, learner).query(ENROLLMENT).optional();
    }

    @Override
    public Optional<Long> claimSeq(UUID courseId, String learner) {
        return db.sql("""
                update enrollment set next_seq = next_seq + 1 where course_id = ? and learner = ?
                returning next_seq - 1""").params(courseId, learner).query(Long.class).optional();
    }
}
