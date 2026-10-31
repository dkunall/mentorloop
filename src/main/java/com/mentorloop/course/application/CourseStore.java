package com.mentorloop.course.application;

import com.mentorloop.adaptive.domain.ConceptGraph;
import com.mentorloop.course.application.CourseViews.Concept;
import com.mentorloop.course.application.CourseViews.Course;
import com.mentorloop.course.application.CourseViews.Enrollment;
import com.mentorloop.course.application.CourseViews.Lesson;
import com.mentorloop.course.application.CourseViews.Module;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CourseStore {

    void insertCourse(Course course);

    Optional<Course> course(UUID id);

    /** Locks the course row so concurrent edits to one course apply one at a time. */
    Optional<Course> lockCourse(UUID id);

    void setPublished(UUID id);

    void insertConcepts(UUID courseId, List<Concept> concepts, List<ConceptGraph.Edge> edges);

    List<Concept> concepts(UUID courseId);

    List<ConceptGraph.Edge> edges(UUID courseId);

    void insertModule(UUID courseId, Module module);

    Optional<UUID> courseOfModule(UUID moduleId);

    int lessonCount(UUID moduleId);

    int moduleCount(UUID courseId);

    void insertLesson(UUID courseId, Lesson lesson);

    Optional<Lesson> lesson(UUID lessonId);

    Optional<UUID> courseOfLesson(UUID lessonId);

    List<Module> modules(UUID courseId);

    List<Lesson> lessons(UUID courseId);

    /** True if this created the enrollment, false if the learner was already enrolled. */
    boolean enroll(Enrollment enrollment);

    Optional<Enrollment> enrollment(UUID courseId, String learner);

    List<Enrollment> enrollments(UUID courseId);

    /** Locks the learner's enrollment row until the transaction ends. Empty if not enrolled. */
    Optional<Enrollment> lockEnrollment(UUID courseId, String learner);

    /** Locks the learner's enrollment row and hands out the next attempt sequence number. */
    Optional<Long> claimSeq(UUID courseId, String learner);

    Map<UUID, String> conceptKeys(UUID courseId);
}
