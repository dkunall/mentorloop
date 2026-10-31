package com.mentorloop.course.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read models the course module hands to other modules. */
public final class CourseViews {

    private CourseViews() {
    }

    public record Course(UUID id, String title, String owner, boolean published, Instant createdAt) {
    }

    public record Concept(UUID id, String key, String name, int pos, List<String> prerequisites) {
    }

    public record Module(UUID id, String title, int pos) {
    }

    public record Lesson(UUID id, UUID moduleId, UUID conceptId, String title, String body, String videoUrl, int pos) {
    }

    public record Enrollment(UUID courseId, String learner, String email, String cohort, Instant enrolledAt) {
    }

    public record Outline(Course course, List<Concept> concepts, List<Module> modules, List<Lesson> lessons) {
    }
}
