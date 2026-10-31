package com.mentorloop.course.application;

import com.mentorloop.course.application.CourseViews.Lesson;

import java.util.UUID;

/**
 * Called in the lesson's transaction so a lesson is never visible without being searchable. Implemented by the
 * tutor, which keeps its own chunk index; the course module does not know how retrieval works.
 */
public interface LessonIndexer {

    void index(UUID courseId, Lesson lesson);
}
